package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.Tag;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * O ticket 105 contra S3 de verdade: o original nasce sem marca e so a ganha quando o Video
 * chega a um estado terminal, pelos eventos que o `extracao` publica. A regra de ciclo de vida
 * que le a marca e do Compose ({@code docker/minio/seed.sh}) e e conferida pelo
 * {@code smoke.sh}; o LocalStack dos Dev Services guarda a regra, mas nao a executa.
 *
 * <p>A marca e gravada depois do {@code UPDATE} terminal, entao a API pode mostrar o terminal um
 * instante antes de ela chegar ao bucket: por isso a espera e pela marca, e nao pelo estado.
 */
@QuarkusTest
class OriginalSoExpiraDepoisDoDesfechoTest {

    private static final String EXCHANGE_DE_EVENTOS = "fiapx.eventos";
    private static final Duration PRAZO_DA_MARCA = Duration.ofSeconds(20);
    private static final List<Tag> MARCA_DO_DESFECHO = List.of(Tag.builder().key("desfecho").value("sim").build());

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    S3AsyncClient s3;

    @Inject
    ArquivoGateway arquivoGateway;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @ConfigProperty(name = "rabbitmq-host")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port")
    int rabbitmqPort;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @Test
    void oOriginalDeUmVideoSemDesfechoNaoTemMarca() {
        var id = enviarVideo();

        assertEquals(List.of(), marcasDoOriginal(id));
    }

    @Test
    void extracaoConcluidaMarcaOOriginal() throws Exception {
        var id = enviarVideo();

        publicar("extracao.concluida", new ExtracaoConcluida(id, id + ".zip", 3, 1_024L, Instant.now()));

        esperarAMarca(id);
    }

    @Test
    void extracaoFalhouMarcaOOriginal() throws Exception {
        var id = enviarVideo();

        publicar("extracao.falhou", new ExtracaoFalhou(
                id, MotivoFalha.ARQUIVO_INVALIDO.name(), "teste do ticket 105", Instant.now()));

        esperarAMarca(id);
    }

    @Test
    void apagarOriginalTiraOObjetoDoBucket() throws Exception {
        var arquivo = Files.createTempFile("orfao", ".mp4");
        Files.writeString(arquivo, "original sem linha");
        var chave = arquivoGateway.gravarVideo(UUID.randomUUID(), "orfao.mp4", arquivo).join();

        arquivoGateway.apagarOriginal(chave).join();

        var falha = assertThrows(CompletionException.class, () -> s3.headObject(
                HeadObjectRequest.builder().bucket(bucketVideos).key(chave).build()).join());
        assertInstanceOf(NoSuchKeyException.class, falha.getCause());
    }

    private UUID enviarVideo() {
        return UUID.fromString(given()
                .auth().oauth2(keycloak.getAccessToken("demo"))
                .multiPart("arquivo", "desfecho.mp4", "video do ticket 105".getBytes(), "video/mp4")
                .when().post("/videos")
                .then().statusCode(202)
                .extract().jsonPath().getString("id"));
    }

    private void publicar(String routingKey, Object evento) throws Exception {
        try (var broker = new BrokerDeTeste(rabbitmqHost, rabbitmqPort, objectMapper)) {
            broker.publicar(EXCHANGE_DE_EVENTOS, routingKey, evento);
        }
    }

    private void esperarAMarca(UUID id) throws InterruptedException {
        var limite = Instant.now().plus(PRAZO_DA_MARCA);
        do {
            if (MARCA_DO_DESFECHO.equals(marcasDoOriginal(id))) {
                return;
            }
            Thread.sleep(100);
        } while (Instant.now().isBefore(limite));
        fail("o original do Video " + id + " nao ganhou a marca do desfecho; tem " + marcasDoOriginal(id));
    }

    private List<Tag> marcasDoOriginal(UUID id) {
        var chave = sessionFactory.withSession(sessao -> sessao.find(VideoEntity.class, id))
                .await().indefinitely().chaveVideo;
        return s3.getObjectTagging(GetObjectTaggingRequest.builder().bucket(bucketVideos).key(chave).build())
                .join().tagSet();
    }
}
