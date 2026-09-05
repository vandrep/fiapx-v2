package br.com.fiapx.videos.framework.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Conexao AMQP propria do teste, ao lado do conector do Quarkus: e o unico jeito de publicar
 * um evento como o {@code extracao} publicaria, e de ver um comando como ele veria.
 *
 * <p>Existe como classe, e nao repetido em cada teste, porque a fabrica de conexao e a espera
 * por mensagem ja nasceram duplicadas em dois arquivos — e a espera, principalmente, tem uma
 * decisao dentro: ela e por <b>prazo</b>, nao por numero de tentativas, senao mensagem de
 * outro teste no mesmo broker consome o orcamento de espera desta.
 *
 * <p>Um canal AMQP nao e thread-safe: cada thread que fala com o broker usa a sua instancia.
 */
final class BrokerDeTeste implements AutoCloseable {

    private static final Duration PAUSA_ENTRE_LEITURAS = Duration.ofMillis(50);

    private final ObjectMapper objectMapper;
    private final Connection conexao;
    private final Channel canal;

    BrokerDeTeste(String host, int porta, ObjectMapper objectMapper) throws Exception {
        this.objectMapper = objectMapper;
        var factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(porta);
        factory.setUsername("guest");
        factory.setPassword("guest");
        conexao = factory.newConnection();
        canal = conexao.createChannel();
    }

    /**
     * O {@code content-type} nao e enfeite: sem ele o {@link JsonObjectPayloadConverter}
     * entrega {@code byte[]} ao consumidor e a mensagem morre em {@code ClassCastException}.
     */
    void publicar(String exchange, String routingKey, Object mensagem) throws Exception {
        canal.basicPublish(exchange, routingKey,
                new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                objectMapper.writeValueAsBytes(mensagem));
    }

    /** Fila propria ligada a uma routing key: o que o worker daquele contrato veria. */
    Espia espiar(String exchange, String routingKey) throws Exception {
        canal.exchangeDeclare(exchange, "topic", true, false, null);
        var fila = canal.queueDeclare().getQueue();
        canal.queueBind(fila, exchange, routingKey);
        return new Espia(fila);
    }

    @Override
    public void close() throws Exception {
        canal.close();
        conexao.close();
    }

    final class Espia {

        private final String fila;

        private Espia(String fila) {
            this.fila = fila;
        }

        /** A primeira mensagem aceita pelo filtro, ou {@code null} se ela nao chegar no prazo. */
        <T> T esperar(Class<T> tipo, Predicate<T> aceita, Duration prazo) throws Exception {
            var limite = Instant.now().plus(prazo);
            do {
                var entrega = canal.basicGet(fila, true);
                if (entrega == null) {
                    Thread.sleep(PAUSA_ENTRE_LEITURAS.toMillis());
                    continue;
                }
                var mensagem = objectMapper.readValue(entrega.getBody(), tipo);
                if (aceita.test(mensagem)) {
                    return mensagem;
                }
            } while (Instant.now().isBefore(limite));
            return null;
        }
    }
}
