package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter;
import br.com.fiapx.videos.core.usecases.video.BaixarPacoteUseCase;
import br.com.fiapx.videos.core.usecases.video.ConsultarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.EnviarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.ListarVideosDoDonoUseCase;
import br.com.fiapx.videos.interfaces.controllers.VideosController;
import br.com.fiapx.videos.interfaces.presenters.VideoPresenterAdapter;
import br.com.fiapx.videos.interfaces.presenters.VideosPaginadosPresenterAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

/**
 * O unico lugar que conhece o grafo de objetos: os use cases sao POJOs sem anotacao de CDI,
 * e e aqui que eles recebem gateways e presenters.
 */
@ApplicationScoped
public class VideosConfiguration {

    @Produces
    VideosController videosController(VideoGateway videoGateway,
                                      ArquivoGateway arquivoGateway,
                                      VideoPresenter videoPresenter,
                                      VideosPaginadosPresenter videosPaginadosPresenter) {
        return new VideosController(
                new EnviarVideoUseCase(arquivoGateway, videoGateway, videoPresenter),
                new ListarVideosDoDonoUseCase(videoGateway, videosPaginadosPresenter),
                new ConsultarVideoUseCase(videoGateway, videoPresenter),
                new BaixarPacoteUseCase(videoGateway, arquivoGateway));
    }

    /** Request-scoped: o presenter guarda o resultado de <b>uma</b> requisicao. */
    @Produces
    @RequestScoped
    VideoPresenterAdapter videoPresenter() {
        return new VideoPresenterAdapter();
    }

    @Produces
    @RequestScoped
    VideosPaginadosPresenterAdapter videosPaginadosPresenter() {
        return new VideosPaginadosPresenterAdapter();
    }
}
