package br.com.fiapx.videos.framework.service;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.utils.async.SimplePublisher;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * O que o ADR 0001 promete na borda: um blip de I/O de segundos contra o MinIO e absorvido
 * pelo {@code onFailure().retry()} do Mutiny no client, e nao vira erro interno para quem
 * chamou a API (tickets 048 e 061).
 *
 * <p>A instabilidade entra pelo {@code S3AsyncClient}, que e onde o blip de verdade mora — o
 * teste troca o bean e observa <b>so</b> a resposta HTTP, entrando pela borda como qualquer
 * outro teste do `videos`. Os dois caminhos sincronos estao cobertos, porque o retry protege
 * os dois: o envio, que grava, e o download, que le.
 *
 * <p>O dublê nao delega ao MinIO de verdade de proposito: durante o teste o proxy do CDI ja
 * aponta para ele, e delegar reentraria em si mesmo. Por isso ele tambem serve os bytes do
 * Pacote — o que esta sob julgamento aqui e o desfecho da requisicao, nao o armazenamento,
 * que os cenarios BDD exercitam de verdade.
 *
 * <h2>A protecao que existe hoje, e o que este cenario custa</h2>
 *
 * <p>A protecao e {@code onFailure().retry()} do Mutiny dentro do
 * {@link ArquivoMinioClient}: 3 repeticoes, jitter de 10%, so sobre {@code Exception}. Nenhum
 * interceptor participa — {@code @Retry} saiu no ticket 061 e as chaves que o configuravam
 * sairam no 064.
 *
 * <p><b>A espera e configuravel por perfil, e aqui vale 1 ms</b>
 * ({@code fiapx.armazenamento.espera-entre-repeticoes}, ticket 080). Com os 2 s de producao
 * estes quatro cenarios ficavam <b>20 s parados</b> — 4 s em cada cenario de blip, 6 s em cada
 * um de armazenamento persistentemente fora — e a classe levava <b>26,5 s</b>; com 1 ms leva
 * <b>6,2 s</b>. O que esta sob teste e a repeticao <i>acontecer</i> e o desfecho que ela
 * produz, nao a duracao da espera, e foi essa a leitura do ticket 048 quando ele comprou o
 * mesmo efeito pela chave do interceptor.
 *
 * <p>O que a espera curta <b>deixa</b> de cobrir, e esta registrado como escolha: que os 2 s do
 * ADR 0001 sejam os 2 s. Esse numero e guardado pelo default do {@code @ConfigProperty}, e nao
 * por cenario — cobra-lo aqui custaria os 20 s de volta para reafirmar uma constante.
 */
@QuarkusTest
class EnvioResisteABlipDoArmazenamentoTest {

    /** O armazenamento que nao volta: falha em toda tentativa, nao so nas primeiras. */
    private static final int SEMPRE = Integer.MAX_VALUE;

    private static final byte[] PACOTE = "pacote de frames para teste".getBytes();

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @Test
    void blipDoArmazenamentoDuranteOEnvioNaoChegaAoChamadorComoErroInterno() {
        QuarkusMock.installMockForType(new S3QueFalhaAsPrimeiras(2), S3AsyncClient.class);

        enviarVideo().then().statusCode(202);
    }

    @Test
    void armazenamentoPersistentementeForaContinuaChegandoComoErroInterno() {
        QuarkusMock.installMockForType(new S3QueFalhaAsPrimeiras(SEMPRE), S3AsyncClient.class);

        enviarVideo().then()
                .statusCode(500)
                .contentType("application/problem+json")
                .body("title", is("Erro interno"));
    }

    @Test
    void blipDoArmazenamentoDuranteODownloadNaoChegaAoChamadorComoErroInterno() {
        var token = keycloak.getAccessToken("demo");
        var id = enviarVideoConcluido(token);

        QuarkusMock.installMockForType(new S3QueFalhaAsPrimeiras(2), S3AsyncClient.class);

        var baixado = given().auth().oauth2(token)
                .when().get("/videos/" + id + "/pacote")
                .then().statusCode(200)
                .extract().asByteArray();

        assertArrayEquals(PACOTE, baixado, "o Pacote tinha de sair inteiro depois do blip");
    }

    @Test
    void armazenamentoPersistentementeForaNoDownloadContinuaChegandoComoErroInterno() {
        var token = keycloak.getAccessToken("demo");
        var id = enviarVideoConcluido(token);

        QuarkusMock.installMockForType(new S3QueFalhaAsPrimeiras(SEMPRE), S3AsyncClient.class);

        given().auth().oauth2(token)
                .when().get("/videos/" + id + "/pacote")
                .then().statusCode(500);
    }

    private Response enviarVideo() {
        return given()
                .auth().oauth2(keycloak.getAccessToken("demo"))
                .multiPart("arquivo", "instavel.mp4", "conteudo de video para teste".getBytes(), "video/mp4")
                .when().post("/videos");
    }

    /**
     * Montagem de cenario, com o armazenamento sadio: envia pela borda e leva o Video a
     * CONCLUIDO pelo banco, como os steps do BDD fazem — a transicao de verdade chega por
     * evento, que e outro assunto. O objeto do Pacote nao vai ao bucket porque quem o serve
     * no cenario do download e o dublê.
     */
    private UUID enviarVideoConcluido(String token) {
        var resposta = given().auth().oauth2(token)
                .multiPart("arquivo", "instavel.mp4", "conteudo de video para teste".getBytes(), "video/mp4")
                .when().post("/videos");
        resposta.then().statusCode(202);
        var id = UUID.fromString(resposta.jsonPath().getString("id"));

        sessionFactory.withTransaction(sessao -> sessao.find(VideoEntity.class, id)
                .invoke(entidade -> {
                    entidade.estado = EstadoVideo.CONCLUIDO;
                    entidade.finalizadoEm = Instant.now();
                    entidade.chavePacote = id + ".zip";
                    entidade.quantidadeFrames = 1;
                    entidade.tamanhoPacoteBytes = (long) PACOTE.length;
                })).await().indefinitely();
        return id;
    }

    /**
     * O armazenamento instavel: as {@code falhasIniciais} primeiras chamadas falham como o SDK
     * falha quando nao alcanca o endpoint, e as seguintes sucedem. A contagem e uma so para
     * gravacao e leitura porque cada cenario exercita um caminho de cada vez.
     */
    private static class S3QueFalhaAsPrimeiras implements S3AsyncClient {

        private final int falhasIniciais;
        private final AtomicInteger tentativas = new AtomicInteger();

        private S3QueFalhaAsPrimeiras(int falhasIniciais) {
            this.falhasIniciais = falhasIniciais;
        }

        @Override
        public CompletableFuture<PutObjectResponse> putObject(PutObjectRequest requisicao, AsyncRequestBody corpo) {
            if (falharDestaVez()) {
                return CompletableFuture.failedFuture(inalcancavel());
            }
            return CompletableFuture.completedFuture(PutObjectResponse.builder().build());
        }

        @Override
        public <T> CompletableFuture<T> getObject(
                GetObjectRequest requisicao, AsyncResponseTransformer<GetObjectResponse, T> transformador) {
            if (falharDestaVez()) {
                return CompletableFuture.failedFuture(inalcancavel());
            }
            var resultado = transformador.prepare();
            transformador.onResponse(GetObjectResponse.builder().contentLength((long) PACOTE.length).build());
            var bytes = new SimplePublisher<ByteBuffer>();
            transformador.onStream(SdkPublisher.adapt(bytes));
            bytes.send(ByteBuffer.wrap(PACOTE));
            bytes.complete();
            return resultado;
        }

        private boolean falharDestaVez() {
            return tentativas.incrementAndGet() <= falhasIniciais;
        }

        private static SdkClientException inalcancavel() {
            return SdkClientException.create("MinIO inalcancavel nesta tentativa");
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
