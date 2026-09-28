package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica {@code ExtrairVideo} no canal {@code extrair-video}, ligado pela config a
 * {@code fiapx.comandos} / {@code extracao.extrair} (docs/contratos/mensagens.md).
 *
 * <p>A recusa do envio e registrada aqui porque, desde o ticket 104, ela nao chega a ninguem
 * mais: o {@code POST /videos} responde {@code 202} assim mesmo e a varredura do ADR 0003 so
 * republica depois da folga. Serve aos dois chamadores — no da varredura, "fica para a
 * reconciliacao" quer dizer a proxima passada.
 *
 * <p>O que esta linha <b>nao</b> cobre: o envio que nao falha nem confirma, como o de um broker
 * em alarme. Ele segue pendente em silencio depois do teto; se o broker o confirmar, grava a
 * marca, e se nao, a varredura o republica e registra isso.
 */
@ApplicationScoped
public class RabbitExtracaoSender implements ExtracaoSender {

    private static final Logger LOG = Logger.getLogger(RabbitExtracaoSender.class);

    @Channel("extrair-video")
    MutinyEmitter<ExtrairVideo> emitter;

    @Override
    public CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
        return emitter.send(new ExtrairVideo(idVideo, chaveVideo, chaveDestinoPacote))
                .onFailure().invoke(falha -> LOG.warnf(falha,
                        "ExtrairVideo sem confirmacao do broker, idVideo=%s; fica para a reconciliacao", idVideo))
                .subscribeAsCompletionStage();
    }
}
