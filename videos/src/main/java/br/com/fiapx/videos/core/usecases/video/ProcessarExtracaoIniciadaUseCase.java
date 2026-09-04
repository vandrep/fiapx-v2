package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoIniciada}: carrega o Video, consulta a entidade e somente
 * tenta o UPDATE condicional quando a transicao e legal (ADR 0002).
 */
public class ProcessarExtracaoIniciadaUseCase {

    private final VideoGateway videoGateway;

    public ProcessarExtracaoIniciadaUseCase(VideoGateway videoGateway) {
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.buscarPorId(command.idVideo())
                .thenCompose(video -> video.isEmpty() || !video.get().marcaComoIniciada()
                        ? CompletableFuture.completedFuture(false)
                        : videoGateway.marcarIniciada(command.idVideo(), command.iniciadaEm()))
                .thenApply(mudou -> null);
    }

    public record Command(UUID idVideo, Instant iniciadaEm) {
    }
}
