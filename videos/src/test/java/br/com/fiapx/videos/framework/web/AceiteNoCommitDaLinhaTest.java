package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.framework.dispatcher.RabbitExtracaoSender;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O aceite do ticket 104 visto de fora: depois do commit da linha, o publish do
 * {@code ExtrairVideo} que falha ou que nao responde nao vira {@code 500}. O Video sai
 * {@code 202} em {@code RECEBIDO}, sem marca, e a varredura do ADR 0003 publica depois — essa
 * metade esta em {@code ReconciliacaoAposPublicacaoInterrompidaTest}.
 *
 * <p>A falha entra pelo {@link ExtracaoSender}, que e onde o broker bloqueado por alarme se
 * manifesta. O cenario do publish travado usa o teto de producao, e nao um reduzido: e ele que
 * esta sob julgamento, e o que ele custa aqui e uma espera de teto.
 *
 * <p>O cenario travado tambem cobre o que o teste do use case nao alcanca: vencido o teto, a
 * continuacao do envio roda na thread do timer do JDK, fora do contexto Vert.x da requisicao, e
 * ainda assim precisa chegar ao presenter e ao {@code Resource}.
 */
@QuarkusTest
class AceiteNoCommitDaLinhaTest {

    /**
     * Folga sobre o teto para o resto do envio: multipart, MinIO, {@code INSERT}, presenter e
     * HTTP. O token e buscado antes de o cronometro partir.
     */
    private static final Duration FOLGA_SOBRE_O_TETO = Duration.ofSeconds(2);

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @ConfigProperty(name = "fiapx.mensageria.teto-do-publish-no-envio", defaultValue = "2s")
    Duration tetoDoPublish;

    @Test
    void publishQueFalhaDepoisDoCommitResponde202SemMarca() {
        QuarkusMock.installMockForType(brokerQueRecusa(), ExtracaoSender.class);

        var id = aceito(enviarVideo(keycloak.getAccessToken("demo")));

        assertNull(marcaDoComando(id), "sem confirmacao, a marca fica para a varredura");
    }

    @Test
    void publishQueNaoRespondeResponde202DentroDoTeto() {
        QuarkusMock.installMockForType(brokerBloqueado(), ExtracaoSender.class);

        var token = keycloak.getAccessToken("demo");
        var inicio = System.nanoTime();
        var resposta = enviarVideo(token);
        var decorrido = Duration.ofNanos(System.nanoTime() - inicio);

        var id = aceito(resposta);
        assertTrue(decorrido.compareTo(tetoDoPublish.plus(FOLGA_SOBRE_O_TETO)) < 0,
                "a requisicao tinha de terminar perto do teto de " + tetoDoPublish + ", levou " + decorrido);
        assertNull(marcaDoComando(id));
    }

    /**
     * Vencido o teto, o envio completa na thread {@code CompletableFuture.Delayer}, que e uma so
     * para o JVM inteiro. Se a borda seguisse ali, o {@code MDC.put} do {@code Rastro} cairia
     * numa ThreadLocal dessa thread e ninguem o soltaria — o vazamento do ticket 063. O teste
     * le o MDC de dentro da mesma thread, por outro timeout.
     */
    @Test
    void publishQueNaoRespondeNaoDeixaOIdVideoNoMdcDaThreadDoTimer() throws Exception {
        QuarkusMock.installMockForType(brokerBloqueado(), ExtracaoSender.class);

        aceito(enviarVideo(keycloak.getAccessToken("demo")));

        var noTimer = new CompletableFuture<Object>()
                .orTimeout(50, TimeUnit.MILLISECONDS)
                .exceptionally(timeout -> MDC.get("idVideo"))
                .get(5, TimeUnit.SECONDS);
        assertNull(noTimer, "a borda seguiu na thread do timer e deixou o idVideo no MDC dela");
    }

    private UUID aceito(Response resposta) {
        resposta.then()
                .statusCode(202)
                .body("estado", is(EstadoVideo.RECEBIDO.name()));
        var id = UUID.fromString(resposta.jsonPath().getString("id"));
        resposta.then().header("Location", endsWith("/videos/" + id));
        return id;
    }

    private Response enviarVideo(String token) {
        return given()
                .auth().oauth2(token)
                .multiPart("arquivo", "aceite.mp4", "conteudo de video para teste".getBytes(), "video/mp4")
                .when().post("/videos");
    }

    private Object marcaDoComando(UUID id) {
        return sessionFactory.withSession(sessao -> sessao.find(VideoEntity.class, id))
                .map(entidade -> entidade.comandoPublicadoEm)
                .await().indefinitely();
    }

    /** Subclasse, e nao lambda: o {@link QuarkusMock} troca o bean pelo tipo da implementacao. */
    private static RabbitExtracaoSender brokerQueRecusa() {
        return new RabbitExtracaoSender() {
            @Override
            public CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker bloqueado por alarme de disco"));
            }
        };
    }

    /** O publish que nunca confirma nem falha, como o de um broker em {@code connection.blocked}. */
    private static RabbitExtracaoSender brokerBloqueado() {
        return new RabbitExtracaoSender() {
            @Override
            public CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
                return new CompletableFuture<>();
            }
        };
    }
}
