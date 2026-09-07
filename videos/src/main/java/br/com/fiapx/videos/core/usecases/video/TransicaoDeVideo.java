package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Forma comum aos tres consumidores de evento de extracao (ticket 053): busca o Video pelo
 * id, deixa a entidade decidir a transicao, curto-circuita quando ela recusa ou o Video nao
 * existe, e so entao grava. A decisao de transicao continua na entidade (ADR 0002); esta
 * classe so evita repetir o encadeamento em volta dela.
 */
final class TransicaoDeVideo {

    private TransicaoDeVideo() {
    }

    static CompletableFuture<Void> processar(VideoGateway videoGateway,
                                              UUID idVideo,
                                              Predicate<Video> transicao,
                                              Function<Video, CompletableFuture<Boolean>> gravar,
                                              BiFunction<Video, Boolean, CompletableFuture<Void>> aposGravar) {
        return videoGateway.buscarPorId(idVideo)
                .thenCompose(video -> video.isEmpty() || !transicao.test(video.get())
                        ? CompletableFuture.<Void>completedFuture(null)
                        : gravar.apply(video.get())
                                .thenCompose(mudou -> aposGravar.apply(video.get(), mudou)));
    }

    static CompletableFuture<Void> semEfeitoPosterior(Video video, Boolean mudou) {
        return CompletableFuture.completedFuture(null);
    }
}
