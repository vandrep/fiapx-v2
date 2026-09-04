package br.com.fiapx.extracao.framework.dispatcher;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Topologia do ticket 029: a {@code extracao.extrair.dlq} deixou de ser terminal e ganhou
 * fundo proprio, a {@code extracao.extrair.estacionamento}. Este teste nao chama o
 * {@code ExtracaoDlqConsumer} direto — publica um payload que ele nao consegue desserializar
 * em {@code ExtrairVideo}, o que falha o processamento e aciona o mesmo caminho que uma falha
 * de publicacao acionaria: nack, {@code failure-strategy=reject} sem requeue, e o
 * {@code x-dead-letter-exchange} da propria fila levando ao estacionamento. Existe para que a
 * topologia nao regrida em silencio: roda a cada {@code ./mvnw test}, sem depender do modo
 * {@code mata-publicacao} do `scripts/carga/conservacao.sh}, que so roda contra o Compose.
 */
@QuarkusTest
class ExtracaoEstacionamentoTest {

    @ConfigProperty(name = "rabbitmq-host")
    String host;

    @ConfigProperty(name = "rabbitmq-port")
    int port;

    @Test
    void mensagemQueFalhaNoConsumoDaDlqChegaAoEstacionamento() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername("guest");
        factory.setPassword("guest");

        try (Connection conexao = factory.newConnection();
             Channel canal = conexao.createChannel()) {
            // Exchange padrao (""): routing key = nome da fila, o mesmo canal que o proprio
            // ExtracaoDlqConsumer usa para consumir extracao.extrair.dlq.
            canal.basicPublish("", "extracao.extrair.dlq", null, "nao-e-json".getBytes());

            GetResponse resposta = aguardarMensagem(canal, "extracao.extrair.estacionamento");
            assertNotNull(resposta, "mensagem deveria ter chegado ao estacionamento");
        }
    }

    private GetResponse aguardarMensagem(Channel canal, String fila) throws IOException, InterruptedException {
        long limite = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < limite) {
            GetResponse resposta = canal.basicGet(fila, true);
            if (resposta != null) {
                return resposta;
            }
            Thread.sleep(200);
        }
        return null;
    }
}
