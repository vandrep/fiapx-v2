package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoFalhou}: RECEBIDO/PROCESSANDO -> FALHOU (ADR 0002), e a guarda
 * de unicidade do e-mail mora aqui (ADR 0001). {@code marcarFalha} so devolve {@code true}
 * quando <b>esta</b> chamada de fato tirou a linha de um desses dois predecessores —
 * reentregas do mesmo evento (o contrato nao garante ordem, e {@code x-delivery-limit} conta
 * entregas) recebem {@code false} e nao publicam nada. Tres entregas do mesmo evento produzem,
 * portanto, exatamente um {@code VideoFalhou}. O publish em si e {@link PublicarVideoFalhou},
 * o mesmo caminho que a reconciliacao usa.
 *
 * <p>A marca do original vem <b>depois</b> do aviso, para que um MinIO fora, gastando as
 * repeticoes, nao atrase o e-mail do Dono, e vem <b>mesmo quando o aviso falha</b>: a reentrega
 * encontraria a linha ja terminal e nao marcaria de novo. A falha do aviso continua subindo, e o
 * aviso fica com a varredura (ticket 105). Forma comum aos consumidores de evento de extracao
 * em {@link TransicaoDeVideo} (ticket 053).
 */
public class ProcessarExtracaoFalhouUseCase {

    private final VideoGateway videoGateway;
    private final ArquivoGateway arquivoGateway;
    private final PublicarVideoFalhou publicarVideoFalhou;

    public ProcessarExtracaoFalhouUseCase(VideoGateway videoGateway,
                                          ArquivoGateway arquivoGateway,
                                          PublicarVideoFalhou publicarVideoFalhou) {
        this.videoGateway = videoGateway;
        this.arquivoGateway = arquivoGateway;
        this.publicarVideoFalhou = publicarVideoFalhou;
    }

    public CompletableFuture<Void> executar(Command command) {
        return TransicaoDeVideo.processar(videoGateway, command.idVideo(),
                video -> video.marcaComoFalha(command.ocorridoEm(), command.motivo()),
                video -> videoGateway.marcarFalha(command.idVideo(), command.ocorridoEm(), command.motivo()),
                (video, mudou) -> avisarSeMudou(video, mudou)
                        .handle((ignorado, falhaDoAviso) -> falhaDoAviso)
                        .thenCompose(falhaDoAviso -> TransicaoDeVideo.marcarDesfechoDoOriginal(arquivoGateway, video)
                                .thenCompose(ignorado -> falhaDoAviso == null
                                        ? CompletableFuture.<Void>completedFuture(null)
                                        : CompletableFuture.<Void>failedFuture(falhaDoAviso))));
    }

    private CompletableFuture<Void> avisarSeMudou(Video video, boolean mudou) {
        return mudou ? publicarVideoFalhou.publicar(video) : CompletableFuture.completedFuture(null);
    }

    public record Command(UUID idVideo, MotivoFalha motivo, Instant ocorridoEm) {
    }
}
