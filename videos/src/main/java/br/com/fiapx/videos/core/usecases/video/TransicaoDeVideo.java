package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
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
 * classe so evita repetir o encadeamento em volta dela — e, desde o ticket 105, o passo que os
 * dois terminais repetem depois de gravar: liberar o original para expirar.
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

    /**
     * Libera o original para expirar, depois do {@code UPDATE} terminal (ticket 105). Marca
     * mesmo quando {@code mudou} e {@code false}: o {@code UPDATE} so altera zero linhas quando
     * a linha ja e terminal, entao a marca continua certa, e repeti-la e idempotente.
     *
     * <p><b>Nunca falha.</b> A transicao ja esta gravada e e o que o Dono observa; a marca so
     * decide quando o original pode sumir. Propagar a falha faria a entrega voltar a fila, a
     * reentrega encontraria a linha terminal e nao marcaria de novo. Sem marca o original fica para sempre: vazamento
     * aceito, nao perda. A marca entra por {@code thenCompose} para que o adapter que lanca, em
     * vez de devolver o future falho, tambem nao escape.
     */
    static CompletableFuture<Void> marcarDesfechoDoOriginal(ArquivoGateway arquivoGateway, Video video) {
        return CompletableFuture.completedFuture(video.chaveVideo())
                .thenCompose(arquivoGateway::marcarDesfechoDoOriginal)
                .exceptionally(marcaQueFalhou -> null);
    }
}
