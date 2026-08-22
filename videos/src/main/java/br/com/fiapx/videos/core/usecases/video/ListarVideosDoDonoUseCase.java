package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Origem do {@code GET /videos}. Escopo pelo dono, sem excecao. */
public class ListarVideosDoDonoUseCase {

    private final VideoGateway videoGateway;
    private final VideosPaginadosPresenter presenter;

    public ListarVideosDoDonoUseCase(VideoGateway videoGateway, VideosPaginadosPresenter presenter) {
        this.videoGateway = videoGateway;
        this.presenter = presenter;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway
                .listarPorDono(command.dono(), command.estado(), command.pagina(), command.tamanho())
                .thenApply(pagina -> new Pagina<>(
                        pagina.conteudo().stream().map(VideoDTO::de).toList(),
                        pagina.pagina(),
                        pagina.tamanho(),
                        pagina.total()))
                .thenAccept(presenter::present);
    }

    public record Command(Dono dono, Optional<EstadoVideo> estado, int pagina, int tamanho) {
    }
}
