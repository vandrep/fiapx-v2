package br.com.fiapx.extracao.framework.dispatcher;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Topologia do ticket 029: a {@code extracao.extrair.dlq} deixou de ser terminal e ganhou
 * fundo proprio, a {@code extracao.extrair.estacionamento}. O
 * {@link CanalExtracaoFalhouQuebradoProfile} quebra so o canal de saida {@code
 * extracao-falhou}, entao quando o {@code ExtracaoDlqConsumer} tenta publicar {@code
 * ExtracaoFalhou} para a mensagem publicada aqui, essa publicacao falha de verdade — o
 * mesmo {@code Uni} que falha em producao quando o broker recusa uma publicacao (emenda do
 * ADR 0001). Essa falha nackeia a mensagem original; {@code failure-strategy=reject} a manda
 * sem requeue, e o {@code x-dead-letter-exchange} da propria fila leva ao estacionamento.
 *
 * <p>Publicar um payload que nao deserializa em {@code ExtrairVideo} nao serve para este
 * teste: a conversao de payload roda fora do {@code Uni} por mensagem que o {@code
 * failure-strategy} trata, e uma excecao ali pode derrubar a subscription do canal inteiro
 * em vez de nackear so esta entrega — o oposto do que o ticket 029 precisa provar. Por isso
 * a mensagem publicada aqui e um {@code ExtrairVideo} valido, e o que falha e a publicacao
 * de saida, nao a leitura de entrada.
 */
@QuarkusTest
@TestProfile(CanalExtracaoFalhouQuebradoProfile.class)
class ExtracaoEstacionamentoTest {

    @ConfigProperty(name = "rabbitmq-host")
    String host;

    @ConfigProperty(name = "rabbitmq-port")
    int port;

    @Test
    void publicacaoFalhaNoConsumoDaDlqChegaAoEstacionamento() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername("guest");
        factory.setPassword("guest");

        UUID idVideo = UUID.randomUUID();
        String corpo = "{\"idVideo\":\"" + idVideo + "\",\"chaveVideo\":\"videos/x.mp4\","
                + "\"chaveDestinoPacote\":\"pacotes/x.zip\"}";
        AMQP.BasicProperties propriedades = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .build();

        try (Connection conexao = factory.newConnection();
             Channel canal = conexao.createChannel()) {
            // Exchange padrao (""): routing key = nome da fila, o mesmo canal que o proprio
            // ExtracaoDlqConsumer usa para consumir extracao.extrair.dlq.
            canal.basicPublish("", "extracao.extrair.dlq", propriedades, corpo.getBytes(StandardCharsets.UTF_8));

            // retry-on-fail-attempts=6 / retry-on-fail-interval=5s no publisher: a publicacao
            // quebrada leva ~20-25s de backoff para desistir antes do nack chegar aqui.
            GetResponse resposta = aguardarMensagem(canal, "extracao.extrair.estacionamento", 45_000);
            assertNotNull(resposta, "mensagem deveria ter chegado ao estacionamento");
        }
    }

    private GetResponse aguardarMensagem(Channel canal, String fila, long limiteMillis) throws Exception {
        long limite = System.currentTimeMillis() + limiteMillis;
        while (System.currentTimeMillis() < limite) {
            GetResponse resposta = canal.basicGet(fila, true);
            if (resposta != null) {
                return resposta;
            }
            Thread.sleep(500);
        }
        return null;
    }
}
