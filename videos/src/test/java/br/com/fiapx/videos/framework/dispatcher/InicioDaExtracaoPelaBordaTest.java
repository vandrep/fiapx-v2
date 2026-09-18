package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * O instante de inicio do ticket 106 pelo caminho de mensageria de verdade: o
 * {@code ExtracaoIniciada} entra pelo RabbitMQ e o {@code iniciada_em} sai do Postgres. O campo
 * sempre esteve no contrato; ate aqui o consumidor o descartava (ticket 033).
 */
@QuarkusTest
class InicioDaExtracaoPelaBordaTest {

    private static final String EXCHANGE_DE_EVENTOS = "fiapx.eventos";
    private static final String ROUTING_KEY_DA_INICIADA = "extracao.iniciada";
    private static final Duration PRAZO_DA_TRANSICAO = Duration.ofSeconds(20);

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @ConfigProperty(name = "rabbitmq-host")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port")
    int rabbitmqPort;

    @Test
    void oInstanteGravadoEODoEvento() throws Exception {
        var id = enviarVideo();
        // Uma hora atras, e nao agora: um instante tomado no consumo tambem cairia "perto de
        // agora", e o teste passaria com o consumidor ignorando o campo.
        var iniciadaEm = Instant.parse("2026-09-14T09:00:00Z");

        publicar(new ExtracaoIniciada(id, iniciadaEm));

        assertEquals(iniciadaEm, processando(id).iniciadaEm);
    }

    @Test
    void eventoSemInstanteAindaTransicionaEGravaUm() throws Exception {
        var id = enviarVideo();

        publicar(new ExtracaoIniciada(id, null));

        assertNotNull(processando(id).iniciadaEm,
                "PROCESSANDO sem instante e invisivel ao alerta de Video preso");
    }

    private UUID enviarVideo() {
        return UUID.fromString(given()
                .auth().oauth2(keycloak.getAccessToken("demo"))
                .multiPart("arquivo", "inicio.mp4", "video do ticket 106".getBytes(), "video/mp4")
                .when().post("/videos")
                .then().statusCode(202)
                .extract().jsonPath().getString("id"));
    }

    private void publicar(ExtracaoIniciada evento) throws Exception {
        try (var broker = new BrokerDeTeste(rabbitmqHost, rabbitmqPort, objectMapper)) {
            broker.publicar(EXCHANGE_DE_EVENTOS, ROUTING_KEY_DA_INICIADA, evento);
        }
    }

    private VideoEntity processando(UUID id) throws InterruptedException {
        var limite = Instant.now().plus(PRAZO_DA_TRANSICAO);
        do {
            var linha = sessionFactory.withSession(sessao -> sessao.find(VideoEntity.class, id))
                    .await().indefinitely();
            if (linha.estado == EstadoVideo.PROCESSANDO) {
                return linha;
            }
            Thread.sleep(100);
        } while (Instant.now().isBefore(limite));
        return fail("o Video " + id + " nao chegou a PROCESSANDO pelo ExtracaoIniciada");
    }
}
