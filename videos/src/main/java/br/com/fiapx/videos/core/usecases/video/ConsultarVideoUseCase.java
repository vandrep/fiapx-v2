package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.exceptions.VideoNaoEncontradoException;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Origem do {@code GET /videos/{id}}. O 404 do Video alheio cai naturalmente do Optional
 * vazio, sem nenhum {@code if} decidindo entre 403 e 404.
 */
public class ConsultarVideoUseCase {

    private final VideoGateway videoGateway;
    private final VideoPresenter videoPresenter;

    public ConsultarVideoUseCase(VideoGateway videoGateway, VideoPresenter videoPresenter) {
        this.videoGateway = videoGateway;
        this.videoPresenter = videoPresenter;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.buscarPorIdEDono(command.id(), command.dono())
                .thenApply(encontrado -> encontrado
                        .orElseThrow(() -> new VideoNaoEncontradoException(command.id())))
                .thenApply(VideoDTO::de)
                .thenAccept(videoPresenter::present);
    }

    public record Command(UUID id, Dono dono) {
    }
}
