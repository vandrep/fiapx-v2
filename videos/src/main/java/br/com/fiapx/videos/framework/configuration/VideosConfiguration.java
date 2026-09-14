package br.com.fiapx.videos.framework.configuration;

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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * O unico lugar que conhece o grafo de objetos: os use cases sao POJOs sem anotacao de CDI,
 * e e aqui que eles recebem gateways e presenters.
 */
@ApplicationScoped
public class VideosConfiguration {

    /**
     * Quanto o {@code POST /videos} espera a confirmacao do {@code ExtrairVideo} depois do commit
     * da linha (ticket 104). Passado o teto, o Video sai aceito e a varredura do ADR 0003 cobre o
     * comando. O default vive aqui, e nao no {@code .properties}, pelo mesmo motivo das esperas
     * de repeticao (ticket 080).
     */
    @ConfigProperty(name = "fiapx.mensageria.teto-do-publish-no-envio", defaultValue = "2s")
    Duration tetoDoPublishNoEnvio;

    @Produces
    @ApplicationScoped
    PublicarExtrairVideo publicarExtrairVideo(ArquivoGateway arquivoGateway,
                                              ExtracaoSender extracaoSender,
                                              VideoGateway videoGateway) {
        return new PublicarExtrairVideo(arquivoGateway, extracaoSender, videoGateway);
    }

    @Produces
    @ApplicationScoped
    PublicarVideoFalhou publicarVideoFalhou(NotificacaoSender notificacaoSender, VideoGateway videoGateway) {
        return new PublicarVideoFalhou(notificacaoSender, videoGateway);
    }

    @Produces
    VideosController videosController(VideoGateway videoGateway,
                                      ArquivoGateway arquivoGateway,
                                      VideoPresenter videoPresenter,
                                      VideosPaginadosPresenter videosPaginadosPresenter,
                                      PublicarExtrairVideo publicarExtrairVideo) {
        return new VideosController(
                new EnviarVideoUseCase(arquivoGateway, videoGateway, publicarExtrairVideo, videoPresenter,
                        tetoDoPublishNoEnvio),
                new ListarVideosDoDonoUseCase(videoGateway, videosPaginadosPresenter),
                new ConsultarVideoUseCase(videoGateway, videoPresenter),
                new BaixarPacoteUseCase(videoGateway, arquivoGateway));
    }

    @Produces
    ExtracaoEventosController extracaoEventosController(VideoGateway videoGateway,
                                                        ArquivoGateway arquivoGateway,
                                                        PublicarVideoFalhou publicarVideoFalhou) {
        return new ExtracaoEventosController(
                new ProcessarExtracaoIniciadaUseCase(videoGateway),
                new ProcessarExtracaoConcluidaUseCase(videoGateway, arquivoGateway),
                new ProcessarExtracaoFalhouUseCase(videoGateway, arquivoGateway, publicarVideoFalhou));
    }

    @Produces
    ReconciliacaoController reconciliacaoController(VideoGateway videoGateway,
                                                    PublicarExtrairVideo publicarExtrairVideo,
                                                    PublicarVideoFalhou publicarVideoFalhou) {
        return new ReconciliacaoController(
                new ReconciliarPublicacoesPendentesUseCase(videoGateway, publicarExtrairVideo, publicarVideoFalhou));
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
