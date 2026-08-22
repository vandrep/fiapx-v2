package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica {@code ExtrairVideo} no canal {@code extrair-video}, ligado pela config a
 * {@code fiapx.comandos} / {@code extracao.extrair} (docs/contratos/mensagens.md).
 */
@ApplicationScoped
public class RabbitExtracaoSender implements ExtracaoSender {

    @Channel("extrair-video")
    MutinyEmitter<ExtrairVideo> emitter;

    @Override
    public CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
        return emitter.send(new ExtrairVideo(idVideo, chaveVideo, chaveDestinoPacote))
                .subscribeAsCompletionStage();
    }
}
