package br.com.fiapx.extracao.framework.dispatcher;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Topologia do ticket 029: a {@code extracao.extrair.dlq} deixou de ser terminal e ganhou
 * fundo proprio, a {@code extracao.extrair.estacionamento}. O
 * {@link CanalExtracaoFalhouQuebradoProfile} quebra so o canal de saida {@code
 * extracao-falhou}, entao quando o {@code ExtracaoDlqConsumer} tenta publicar {@code
 * ExtracaoFalhou} para a mensagem publicada aqui, o broker fecha o canal. No Vert.x 4.5.24
 * isso deixa o confirm pendente: o prazo do sender transforma a ausencia de confirmacao
 * em falha (ticket 037). Essa falha nackeia a mensagem original; {@code failure-strategy=reject} a manda
 * sem requeue, e o {@code x-dead-letter-exchange} da propria fila leva ao estacionamento.
 *
 * <p>Publicar um payload que nao deserializa em {@code ExtrairVideo} nao serve para este
 * teste: a conversao de payload roda fora do {@code Uni} por mensagem que o {@code
 * failure-strategy} trata, e uma excecao ali pode derrubar a subscription do canal inteiro
 * em vez de nackear so esta entrega — o oposto do que o ticket 029 precisa provar. Por isso
 * a mensagem publicada aqui e um {@code ExtrairVideo} valido, e o que falha e a publicacao
 * de saida, nao a leitura de entrada.
 *
 * <p>A reabertura do ticket 029 acrescentou o caminho que faltava: o segundo teste entra por
 * {@code fiapx.comandos}, produz uma falha permanente imediata e prova que a recusa da propria
 * publicacao de {@code ExtracaoFalhou} leva o comando original a DLQ e depois ao Estacionamento,
 * sem depender do contador de entregas que o ticket 075 mediu como insuficiente.
 */
@QuarkusTest
@TestProfile(CanalExtracaoFalhouQuebradoProfile.class)
class ExtracaoEstacionamentoTest {

    @ConfigProperty(name = "rabbitmq-host")
    String host;

    @ConfigProperty(name = "rabbitmq-port")
    int port;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @Inject
    S3AsyncClient s3;

    @Test
    void publicacaoFalhaNoConsumoDaDlqChegaAoEstacionamento() throws Exception {
        ConnectionFactory factory = fabricaDeConexao();

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

            // O prazo do sender cobre inclusive confirm pendente por fechamento do canal;
            // retry-on-fail so atua quando a publicacao conclui com falha (ticket 037).
            GetResponse resposta = aguardarMensagem(canal, "extracao.extrair.estacionamento", 45_000);
            assertNotNull(resposta, "mensagem deveria ter chegado ao estacionamento");
            assertArrayEquals(corpo.getBytes(StandardCharsets.UTF_8), resposta.getBody(),
                    "o Estacionamento deve preservar o comando original para intervencao humana");
        }
    }

    @Test
    void falhaPermanenteCujaPublicacaoFalhaNaoRecirculaEChegaAoEstacionamento() throws Exception {
        ConnectionFactory factory = fabricaDeConexao();
        UUID idVideo = UUID.randomUUID();
        String chaveVideo = "videos/" + idVideo + ".txt";
        String corpo = "{\"idVideo\":\"" + idVideo + "\",\"chaveVideo\":\"" + chaveVideo + "\","
                + "\"chaveDestinoPacote\":\"pacotes/" + idVideo + ".zip\"}";
        Path arquivoInvalido = Path.of("src/test/resources/fixtures/arquivo-invalido.txt");
        s3.putObject(PutObjectRequest.builder().bucket(bucketVideos).key(chaveVideo).build(),
                AsyncRequestBody.fromBytes(Files.readAllBytes(arquivoInvalido))).get();

        try (Connection conexao = factory.newConnection();
             Channel canal = conexao.createChannel()) {
            canal.queuePurge("extracao.extrair.estacionamento");
            canal.basicPublish("fiapx.comandos", "extracao.extrair",
                    new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                    corpo.getBytes(StandardCharsets.UTF_8));

            GetResponse resposta = aguardarMensagem(canal, "extracao.extrair.estacionamento", 90_000);
            assertNotNull(resposta, "falha permanente não deve recircular em extracao.extrair");
            assertArrayEquals(corpo.getBytes(StandardCharsets.UTF_8), resposta.getBody());
        }
    }

    private ConnectionFactory fabricaDeConexao() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername("guest");
        factory.setPassword("guest");
        return factory;
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
