package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

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
 * <p>O retorno conta o que foi republicado. Nao e telemetria de enfeite: ate o ticket 027 a
 * varredura era <b>silenciosa</b>, e por isso o ADR 0003 descrevia uma garantia que nenhuma
 * medicao jamais tinha observado agir — o que o 025 tentou e nao conseguiu. Quem imprime o
 * numero e o {@code @Scheduled} em {@code framework}; o {@code core} so o devolve.
 *
 * <p><b>Uma folga so, para as duas metades</b> (ticket 050): a janela entre gravar e
 * publicar e a mesma dos dois lados — INSERT -> publish do comando, UPDATE para FALHOU ->
 * publish do evento —, entao o mesmo instante de corte governa as duas buscas. Sem folga na
 * metade da falha, uma varredura que caisse sobre um {@code ProcessarExtracaoFalhouUseCase}
 * em voo republicaria o {@code VideoFalhou} e duplicaria a notificacao — tolerado pelo
 * ADR 0001, mas sem razao para acontecer.
 *
 * <p>Duas replicas varrendo ao mesmo tempo republicam as duas: aceito, o consumo e
 * idempotente (ADR 0003). <b>Dentro de uma unica varredura</b>, porem, os republishes rodam
 * em <b>sequencia</b>, nao em paralelo: a sessao reativa do Hibernate que o
 * {@code Panache.withSession}/{@code withTransaction} reusa por contexto do Vert.x nao
 * tolera duas consultas concorrentes no mesmo contexto — medido em dev, onde duas queries
 * disparadas juntas corrompiam o {@code LoadContexts} do Hibernate Reactive
 * (`NoSuchElementException` em `StandardStack.pop`). O "mesmo par" que o comentario acima
 * promete e literalmente {@link PublicarExtrairVideo} e {@link PublicarVideoFalhou} — os
 * mesmos objetos que {@code EnviarVideoUseCase} e {@code ProcessarExtracaoFalhouUseCase}
 * chamam, nao uma reimplementacao.
 */
public class ReconciliarPublicacoesPendentesUseCase {

    private static final int TAMANHO_DO_LOTE = 100;
    private static final int FOLGA_CONTRA_CRASH_MINUTOS = 1;

    private final VideoGateway videoGateway;
    private final PublicarExtrairVideo publicarExtrairVideo;
    private final PublicarVideoFalhou publicarVideoFalhou;

    public ReconciliarPublicacoesPendentesUseCase(VideoGateway videoGateway,
                                                  PublicarExtrairVideo publicarExtrairVideo,
                                                  PublicarVideoFalhou publicarVideoFalhou) {
        this.videoGateway = videoGateway;
        this.publicarExtrairVideo = publicarExtrairVideo;
        this.publicarVideoFalhou = publicarVideoFalhou;
    }

    public CompletableFuture<Republicacoes> executar() {
        var instanteDeCorte = Instant.now().minus(FOLGA_CONTRA_CRASH_MINUTOS, ChronoUnit.MINUTES);
        var comandos = new int[1];
        return videoGateway.buscarComandosPendentes(instanteDeCorte, TAMANHO_DO_LOTE)
                .thenCompose(pendentes -> {
                    comandos[0] = pendentes.size();
                    return emSequencia(pendentes, publicarExtrairVideo::publicar);
                })
                .thenCompose(ignorado -> videoGateway.buscarFalhasPendentes(instanteDeCorte, TAMANHO_DO_LOTE))
                .thenCompose(pendentes -> emSequencia(pendentes, publicarVideoFalhou::publicar)
                        .thenApply(ignorado -> new Republicacoes(comandos[0], pendentes.size())));
    }

    /** Quantos {@code ExtrairVideo} e quantos {@code VideoFalhou} esta passada republicou. */
    public record Republicacoes(int comandos, int falhas) {

        public boolean houveAlgo() {
            return comandos > 0 || falhas > 0;
        }
    }

    private static CompletableFuture<Void> emSequencia(List<Video> itens, Function<Video, CompletableFuture<Void>> acao) {
        var resultado = CompletableFuture.<Void>completedFuture(null);
        for (var item : itens) {
            resultado = resultado.thenCompose(ignorado -> acao.apply(item));
        }
        return resultado;
    }
}
