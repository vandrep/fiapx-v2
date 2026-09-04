package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoFalhou}: PROCESSANDO -> FALHOU, e a guarda de unicidade do
 * e-mail mora aqui (ADR 0001). {@code marcarFalha} so devolve o Vídeo preenchido quando
 * <b>esta</b> chamada de fato tirou a linha de PROCESSANDO — reentregas do mesmo evento
 * (o contrato nao garante ordem, e {@code x-delivery-limit} conta entregas) encontram o
 * Optional vazio e nao publicam nada. Tres entregas do mesmo evento produzem, portanto,
 * exatamente um {@code VideoFalhou}. O publish em si e {@link PublicarVideoFalhou}, o mesmo
 * caminho que a reconciliacao usa.
 */
public class ProcessarExtracaoFalhouUseCase {

    private final VideoGateway videoGateway;
    private final PublicarVideoFalhou publicarVideoFalhou;

    public ProcessarExtracaoFalhouUseCase(VideoGateway videoGateway, PublicarVideoFalhou publicarVideoFalhou) {
        this.videoGateway = videoGateway;
        this.publicarVideoFalhou = publicarVideoFalhou;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.buscarPorId(command.idVideo())
                .thenCompose(video -> {
                    if (video.isEmpty() || !video.get().marcaComoFalha(command.ocorridoEm(), command.motivo())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return videoGateway.marcarFalha(command.idVideo(), command.ocorridoEm(), command.motivo())
                            .thenCompose(mudou -> mudou
                                    ? publicarVideoFalhou.publicar(video.get())
                                    : CompletableFuture.completedFuture(null));
                });
    }

    public record Command(UUID idVideo, MotivoFalha motivo, Instant ocorridoEm) {
    }
}
