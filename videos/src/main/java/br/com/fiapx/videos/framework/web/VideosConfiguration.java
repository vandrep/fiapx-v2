package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;
import br.com.fiapx.videos.core.usecases.video.BaixarPacoteUseCase;
import br.com.fiapx.videos.core.usecases.video.ConsultarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.EnviarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.ListarVideosDoDonoUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoConcluidaUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoFalhouUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoIniciadaUseCase;
import br.com.fiapx.videos.core.usecases.video.PublicarExtrairVideo;
import br.com.fiapx.videos.core.usecases.video.PublicarVideoFalhou;
import br.com.fiapx.videos.core.usecases.video.ReconciliarPublicacoesPendentesUseCase;
import br.com.fiapx.videos.interfaces.controllers.ExtracaoEventosController;
import br.com.fiapx.videos.interfaces.controllers.ReconciliacaoController;
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
                                      ExtracaoSender extracaoSender,
                                      VideoPresenter videoPresenter,
                                      VideosPaginadosPresenter videosPaginadosPresenter) {
        return new VideosController(
                new EnviarVideoUseCase(arquivoGateway, videoGateway,
                        new PublicarExtrairVideo(arquivoGateway, extracaoSender, videoGateway), videoPresenter),
                new ListarVideosDoDonoUseCase(videoGateway, videosPaginadosPresenter),
                new ConsultarVideoUseCase(videoGateway, videoPresenter),
                new BaixarPacoteUseCase(videoGateway, arquivoGateway));
    }

    @Produces
    ExtracaoEventosController extracaoEventosController(VideoGateway videoGateway,
                                                        NotificacaoSender notificacaoSender) {
        return new ExtracaoEventosController(
                new ProcessarExtracaoIniciadaUseCase(videoGateway),
                new ProcessarExtracaoConcluidaUseCase(videoGateway),
                new ProcessarExtracaoFalhouUseCase(videoGateway,
                        new PublicarVideoFalhou(notificacaoSender, videoGateway)));
    }

    @Produces
    ReconciliacaoController reconciliacaoController(VideoGateway videoGateway,
                                                    ArquivoGateway arquivoGateway,
                                                    ExtracaoSender extracaoSender,
                                                    NotificacaoSender notificacaoSender) {
        return new ReconciliacaoController(new ReconciliarPublicacoesPendentesUseCase(
                videoGateway,
                new PublicarExtrairVideo(arquivoGateway, extracaoSender, videoGateway),
                new PublicarVideoFalhou(notificacaoSender, videoGateway)));
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
