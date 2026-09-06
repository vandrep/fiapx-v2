package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.MotivoFalha;
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
 * o mesmo caminho que a reconciliacao usa. Forma comum aos consumidores de evento de extracao
 * em {@link TransicaoDeVideo} (ticket 053).
 */
public class ProcessarExtracaoFalhouUseCase {

    private final VideoGateway videoGateway;
    private final PublicarVideoFalhou publicarVideoFalhou;

    public ProcessarExtracaoFalhouUseCase(VideoGateway videoGateway, PublicarVideoFalhou publicarVideoFalhou) {
        this.videoGateway = videoGateway;
        this.publicarVideoFalhou = publicarVideoFalhou;
    }

    public CompletableFuture<Void> executar(Command command) {
        return TransicaoDeVideo.processar(videoGateway, command.idVideo(),
                video -> video.marcaComoFalha(command.ocorridoEm(), command.motivo()),
                video -> videoGateway.marcarFalha(command.idVideo(), command.ocorridoEm(), command.motivo()),
                (video, mudou) -> mudou
                        ? publicarVideoFalhou.publicar(video)
                        : CompletableFuture.completedFuture(null));
    }

    public record Command(UUID idVideo, MotivoFalha motivo, Instant ocorridoEm) {
    }
}
