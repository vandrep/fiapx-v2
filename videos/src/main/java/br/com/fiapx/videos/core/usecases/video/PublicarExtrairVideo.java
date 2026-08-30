package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * O caminho unico de publicacao do comando {@code ExtrairVideo} (ADR 0003): envia, depois
 * marca {@code comando_publicado_em}. Chamado tanto pelo caminho normal
 * ({@code EnviarVideoUseCase}) quanto pela varredura de reconciliacao
 * ({@code ReconciliarPublicacoesPendentesUseCase}) — um caminho, dois chamadores, agora de
 * fato no codigo.
 */
public class PublicarExtrairVideo {

    private final ArquivoGateway arquivoGateway;
    private final ExtracaoSender extracaoSender;
    private final VideoGateway videoGateway;

    public PublicarExtrairVideo(ArquivoGateway arquivoGateway,
                                ExtracaoSender extracaoSender,
                                VideoGateway videoGateway) {
        this.arquivoGateway = arquivoGateway;
        this.extracaoSender = extracaoSender;
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> publicar(Video video) {
        var chaveDestinoPacote = arquivoGateway.chaveDoPacote(video.id());
        return extracaoSender
                .enviarExtrairVideo(video.id(), video.chaveVideo(), chaveDestinoPacote)
                .thenCompose(ignorado -> videoGateway.marcarComandoPublicado(video.id(), Instant.now()));
    }
}
