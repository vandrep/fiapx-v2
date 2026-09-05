package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A fronteira observavel do ticket 040: o evento entra pelo RabbitMQ e o desfecho volta pela
 * API, sem chamar consumer, controller ou use case direto.
 *
 * <p>O evento e mesmo <b>rapido</b>: a {@code ExtracaoFalhou} e publicada no instante em que o
 * {@code ExtrairVideo} aparece no broker, o que acontece <b>dentro</b> do {@code POST /videos},
 * antes de ele responder 202. Publicar depois do 202 nao diria nada sobre tempo — a linha ja
 * estaria commitada e marcada de qualquer jeito.
 *
 * <p><b>O que este teste nao faz.</b> Ele nao reprova o defeito do ticket 040 sozinho: medido
 * por mutacao, com {@code @WithTransaction} de volta no {@code VideosResource} ele continua
 * verde, porque a janela entre o publish e o commit e curta demais para ser vencida por uma
 * ida e volta ao broker. Quem reproduz a corrida de forma deterministica e
 * {@code VideoDataSourceAdapterTest.envioDentroDeUmaTransacaoConfirmaOComandoSemOVideoEstarVisivel},
 * e quem cerca a volta do defeito e {@code BordaDoEnvioSemTransacaoTest}. O papel <b>deste</b>
 * teste e o outro lado do aceite: que o desfecho de um evento chegado durante o envio aparece
 * pela API, pelo caminho de mensageria de verdade.
 */
@QuarkusTest
class ExtracaoRapidaPelaBordaTest {

    private static final String EXCHANGE_DE_COMANDOS = "fiapx.comandos";
    private static final String ROUTING_KEY_DO_COMANDO = "extracao.extrair";
    private static final String EXCHANGE_DE_EVENTOS = "fiapx.eventos";
    private static final String ROUTING_KEY_DA_FALHA = "extracao.falhou";
    private static final Duration PRAZO_DO_COMANDO = Duration.ofSeconds(20);
    private static final Duration PRAZO_DO_DESFECHO = Duration.ofSeconds(20);

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "rabbitmq-host")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port")
    int rabbitmqPort;

    @Test
    void falhaPermanenteQueChegaAntesDoFimDoEnvioApareceNaApi() throws Exception {
        var token = keycloak.getAccessToken("demo");

        try (var broker = new BrokerDeTeste(rabbitmqHost, rabbitmqPort, objectMapper)) {
            var espia = broker.espiar(EXCHANGE_DE_COMANDOS, ROUTING_KEY_DO_COMANDO);
            var respondido = responderAoComandoComFalhaPermanente(espia);

            var id = enviarVideo(token);

            var idRespondido = respondido.join();
            assertNotNull(idRespondido, "o ExtrairVideo do envio tinha de aparecer no broker");
            assertEquals(id, idRespondido,
                    "o comando visto foi de outro Video: outro teste publicou no mesmo broker");
            assertEquals(MotivoFalha.ARQUIVO_INVALIDO.name(), motivoQuandoFalhar(token, id));
        }
    }

    /**
     * A resposta sai de outra thread, com conexao propria, porque ela precisa acontecer
     * <b>durante</b> o {@code POST}: o comando chega ao broker antes de o envio terminar.
     */
    private CompletableFuture<UUID> responderAoComandoComFalhaPermanente(BrokerDeTeste.Espia espia) {
        return CompletableFuture.supplyAsync(() -> {
            try (var respostas = new BrokerDeTeste(rabbitmqHost, rabbitmqPort, objectMapper)) {
                var comando = espia.esperar(ExtrairVideo.class, qualquer -> true, PRAZO_DO_COMANDO);
                if (comando == null) {
                    return null;
                }
                respostas.publicar(EXCHANGE_DE_EVENTOS, ROUTING_KEY_DA_FALHA, new ExtracaoFalhou(
                        comando.idVideo(), MotivoFalha.ARQUIVO_INVALIDO.name(), "teste controlado", Instant.now()));
                return comando.idVideo();
            } catch (Exception falha) {
                throw new IllegalStateException("o respondedor do comando quebrou", falha);
            }
        });
    }

    private UUID enviarVideo(String token) {
        return UUID.fromString(given()
                .auth().oauth2(token)
                .multiPart("arquivo", "rapido.mp4", "video para evento rapido".getBytes(), "video/mp4")
                .when().post("/videos")
                .then().statusCode(202)
                .extract().jsonPath().getString("id"));
    }

    /** O motivo publicado pela API quando o Video chega a FALHOU dentro do prazo. */
    private String motivoQuandoFalhar(String token, UUID id) throws InterruptedException {
        var limite = Instant.now().plus(PRAZO_DO_DESFECHO);
        do {
            var resposta = given().auth().oauth2(token).when().get("/videos/" + id);
            if (resposta.statusCode() == 200
                    && EstadoVideo.FALHOU.name().equals(resposta.jsonPath().getString("estado"))) {
                return resposta.jsonPath().getString("motivo");
            }
            Thread.sleep(100);
        } while (Instant.now().isBefore(limite));
        return fail("a API nao expos FALHOU para o evento rapido do Video " + id);
    }
}
