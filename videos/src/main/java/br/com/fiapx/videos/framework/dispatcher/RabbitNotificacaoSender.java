package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica {@code VideoFalhou} no canal {@code video-falhou}, ligado pela config a
 * {@code fiapx.eventos} / {@code video.falhou} (docs/contratos/mensagens.md).
 */
@ApplicationScoped
public class RabbitNotificacaoSender implements NotificacaoSender {

    @Channel("video-falhou")
    MutinyEmitter<VideoFalhou> emitter;

    @Override
    public CompletableFuture<Void> enviarVideoFalhou(UUID idVideo,
                                                      Dono dono,
                                                      String nomeArquivoOriginal,
                                                      MotivoFalha motivo,
                                                      Instant ocorridoEm) {
        return emitter.send(new VideoFalhou(
                        idVideo,
                        dono.sub(),
                        dono.email(),
                        nomeArquivoOriginal,
                        motivo.name(),
                        ocorridoEm))
                .subscribeAsCompletionStage();
    }
}
