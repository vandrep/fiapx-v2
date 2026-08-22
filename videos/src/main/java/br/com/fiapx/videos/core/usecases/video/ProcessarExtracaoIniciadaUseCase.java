package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoIniciada}: RECEBIDO -> PROCESSANDO. Sem regra alem de chamar
 * o gateway — o {@code true}/{@code false} de volta nao importa aqui, uma reentrega fora de
 * ordem so significa "nao mudou nada" (ADR 0002).
 */
public class ProcessarExtracaoIniciadaUseCase {

    private final VideoGateway videoGateway;

    public ProcessarExtracaoIniciadaUseCase(VideoGateway videoGateway) {
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.marcarIniciada(command.idVideo(), command.iniciadaEm())
                .thenApply(ignorado -> null);
    }

    public record Command(UUID idVideo, Instant iniciadaEm) {
    }
}
