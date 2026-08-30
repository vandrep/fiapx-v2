package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * O caminho unico de publicacao do evento {@code VideoFalhou} (ADR 0003): envia, depois marca
 * {@code falha_publicada_em}. Chamado tanto pelo caminho normal
 * ({@code ProcessarExtracaoFalhouUseCase}) quanto pela varredura de reconciliacao
 * ({@code ReconciliarPublicacoesPendentesUseCase}) — um caminho, dois chamadores, agora de
 * fato no codigo.
 */
public class PublicarVideoFalhou {

    private final NotificacaoSender notificacaoSender;
    private final VideoGateway videoGateway;

    public PublicarVideoFalhou(NotificacaoSender notificacaoSender, VideoGateway videoGateway) {
        this.notificacaoSender = notificacaoSender;
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> publicar(Video video) {
        return notificacaoSender
                .enviarVideoFalhou(video.id(), video.dono(), video.nome(), video.motivo(), video.finalizadoEm())
                .thenCompose(ignorado -> videoGateway.marcarFalhaPublicada(video.id(), Instant.now()));
    }
}
