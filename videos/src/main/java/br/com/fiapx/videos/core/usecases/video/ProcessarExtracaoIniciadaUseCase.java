package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoIniciada}: carrega o Video, consulta a entidade e somente
 * tenta o UPDATE condicional quando a transicao e legal (ADR 0002). Forma comum aos
 * consumidores de evento de extracao em {@link TransicaoDeVideo} (ticket 053).
 */
public class ProcessarExtracaoIniciadaUseCase {

    private final VideoGateway videoGateway;

    public ProcessarExtracaoIniciadaUseCase(VideoGateway videoGateway) {
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return TransicaoDeVideo.processar(videoGateway, command.idVideo(),
                video -> video.marcaComoIniciada(command.iniciadaEm()),
                video -> videoGateway.marcarIniciada(command.idVideo(), command.iniciadaEm()),
                TransicaoDeVideo::semEfeitoPosterior);
    }

    public record Command(UUID idVideo, Instant iniciadaEm) {
    }
}
