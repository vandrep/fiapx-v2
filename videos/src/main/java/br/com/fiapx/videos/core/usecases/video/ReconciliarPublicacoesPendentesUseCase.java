package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A tabela {@code video} e o outbox (ADR 0003): esta varredura republica o que o
 * {@code EnviarVideoUseCase} e o {@code ProcessarExtracaoFalhouUseCase} podem ter deixado
 * pendente entre o publish e a marca, num crash. Chama <b>o mesmo</b> par
 * sender/{@code marcarXPublicado} que o caminho normal chama — um caminho proprio de reenvio
 * poderia montar a chave do MinIO de outro jeito e virar fonte de bug.
 *
 * <p>Duas replicas varrendo ao mesmo tempo republicam as duas: aceito, o consumo e
 * idempotente (ADR 0003). <b>Dentro de uma unica varredura</b>, porem, os republishes rodam
 * em <b>sequencia</b>, nao em paralelo: a sessao reativa do Hibernate que o
 * {@code Panache.withSession}/{@code withTransaction} reusa por contexto do Vert.x nao
 * tolera duas consultas concorrentes no mesmo contexto — medido em dev, onde duas queries
 * disparadas juntas corrompiam o {@code LoadContexts} do Hibernate Reactive
 * (`NoSuchElementException` em `StandardStack.pop`).
 */
public class ReconciliarPublicacoesPendentesUseCase {

    private static final int TAMANHO_DO_LOTE = 100;
    private static final int FOLGA_CONTRA_CRASH_MINUTOS = 1;

    private final VideoGateway videoGateway;
    private final ArquivoGateway arquivoGateway;
    private final ExtracaoSender extracaoSender;
    private final NotificacaoSender notificacaoSender;

    public ReconciliarPublicacoesPendentesUseCase(VideoGateway videoGateway,
                                                  ArquivoGateway arquivoGateway,
                                                  ExtracaoSender extracaoSender,
                                                  NotificacaoSender notificacaoSender) {
        this.videoGateway = videoGateway;
        this.arquivoGateway = arquivoGateway;
        this.extracaoSender = extracaoSender;
        this.notificacaoSender = notificacaoSender;
    }

    public CompletableFuture<Void> executar() {
        var recebidosAntesDe = Instant.now().minus(FOLGA_CONTRA_CRASH_MINUTOS, ChronoUnit.MINUTES);
        return videoGateway.buscarComandosPendentes(recebidosAntesDe, TAMANHO_DO_LOTE)
                .thenCompose(pendentes -> emSequencia(pendentes, this::republicarComando))
                .thenCompose(ignorado -> videoGateway.buscarFalhasPendentes(TAMANHO_DO_LOTE))
                .thenCompose(pendentes -> emSequencia(pendentes, this::republicarFalha));
    }

    private CompletableFuture<Void> republicarComando(Video video) {
        return extracaoSender
                .enviarExtrairVideo(video.id(), video.chaveVideo(), arquivoGateway.chaveDoPacote(video.id()))
                .thenCompose(ignorado -> videoGateway.marcarComandoPublicado(video.id(), Instant.now()));
    }

    private CompletableFuture<Void> republicarFalha(Video video) {
        return notificacaoSender
                .enviarVideoFalhou(video.id(), video.dono(), video.nome(), video.motivo(), video.finalizadoEm())
                .thenCompose(ignorado -> videoGateway.marcarFalhaPublicada(video.id(), Instant.now()));
    }

    private static CompletableFuture<Void> emSequencia(List<Video> itens, Function<Video, CompletableFuture<Void>> acao) {
        var resultado = CompletableFuture.<Void>completedFuture(null);
        for (var item : itens) {
            resultado = resultado.thenCompose(ignorado -> acao.apply(item));
        }
        return resultado;
    }
}
