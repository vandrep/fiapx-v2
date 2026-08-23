package br.com.fiapx.notificacao.framework.dispatcher;

import io.smallrye.reactive.messaging.MessageConverter;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.lang.reflect.Type;

/**
 * O conector RabbitMQ decodifica todo payload {@code content-type: application/json} em
 * {@link JsonObject} — nunca no tipo do record do canal (achado do ticket 020, testando o
 * Compose ponta a ponta: nenhum teste publica mensagem de verdade pela fila, todos chamam o
 * controller direto). Sem este converter, o invoker gerado tenta um cast direto de
 * {@code JsonObject} para o record e explode em {@link ClassCastException} no primeiro
 * consumo real.
 */
@ApplicationScoped
public class JsonObjectPayloadConverter implements MessageConverter {

    @Override
    public boolean canConvert(Message<?> message, Type target) {
        return message.getPayload() instanceof JsonObject && target instanceof Class;
    }

    @Override
    public Message<?> convert(Message<?> message, Type target) {
        JsonObject payload = (JsonObject) message.getPayload();
        return message.withPayload(payload.mapTo((Class<?>) target));
    }
}
