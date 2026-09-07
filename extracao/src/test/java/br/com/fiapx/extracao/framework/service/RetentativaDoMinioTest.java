package br.com.fiapx.extracao.framework.service;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que o ADR 0001 promete no acesso ao MinIO: um blip de I/O e absorvido por
 * {@link ArquivoMinioClient}, e um armazenamento que nao volta acaba falhando em vez de
 * insistir para sempre.
 *
 * <p>Este teste substitui o {@code RetryComCompletionStageTest}, que travava o comportamento
 * do {@code @Retry} do SmallRye Fault Tolerance. O ticket 061 tirou aquele interceptor daqui —
 * ele reagendava a chamada no contexto Vert.x do consumidor e a prendia la para sempre —, e o
 * que precisa continuar travado e a <b>politica</b>, nao a anotacao que a implementava: o
 * numero de tentativas e o fato de a ultima falha chegar ao chamador.
 *
 * <p>Sem container de proposito: o bean tem um campo so, e monta-lo a mao deixa o teste rodar
 * em milissegundos de CPU — o que ele espera e a espera de 2 s do ADR 0001, nao boot.
 */
class RetentativaDoMinioTest {

    private static final byte[] VIDEO = "conteudo de video para teste".getBytes();

    /** O armazenamento que nao volta: falha em toda tentativa, nao so nas primeiras. */
    private static final int SEMPRE = Integer.MAX_VALUE;

    @Test
    void blipNoDownloadEAbsorvidoPelaRetentativa() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(2);
        var destino = Files.createTempDirectory("t061").resolve("original.mp4");

        var baixado = clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get();

        assertEquals(destino, baixado);
        assertTrue(Files.exists(destino), "o Video tinha de estar em disco depois do blip");
        assertEquals(3, s3.tentativas(), "duas falhas mais a que sucedeu");
    }

    @Test
    void armazenamentoPersistentementeForaFalhaEmVezDeInsistirParaSempre() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(SEMPRE);
        var destino = Files.createTempDirectory("t061").resolve("original.mp4");

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get());

        assertTrue(falha.getCause() instanceof SdkClientException,
                "a ultima falha do MinIO tinha de chegar ao chamador, e chegou " + falha.getCause());
        assertEquals(4, s3.tentativas(), "a primeira tentativa mais as tres retentativas do ADR 0001");
    }

    @Test
    void blipNoUploadEAbsorvidoPelaRetentativa() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(2);
        var origem = Files.createTempFile("t061", ".zip");
        Files.write(origem, VIDEO);

        clienteCom(s3).gravar("pacotes", "chave.zip", origem).toCompletableFuture().get();

        assertEquals(3, s3.tentativas(), "duas falhas mais a que sucedeu");
    }

    private static ArquivoMinioClient clienteCom(S3AsyncClient s3) {
        var cliente = new ArquivoMinioClient();
        cliente.s3 = s3;
        return cliente;
    }

    /**
     * O armazenamento instavel, no mesmo desenho do dublê que o `videos` ja usa: as
     * {@code falhasIniciais} primeiras chamadas falham como o SDK falha quando nao alcanca o
     * endpoint, e as seguintes sucedem.
     */
    private static class S3QueFalhaAsPrimeiras implements S3AsyncClient {

        private final int falhasIniciais;
        private final AtomicInteger tentativas = new AtomicInteger();

        private S3QueFalhaAsPrimeiras(int falhasIniciais) {
            this.falhasIniciais = falhasIniciais;
        }

        private int tentativas() {
            return tentativas.get();
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
            transformador.onResponse(GetObjectResponse.builder().contentLength((long) VIDEO.length).build());
            var bytes = new SimplePublisher<ByteBuffer>();
            transformador.onStream(SdkPublisher.adapt(bytes));
            bytes.send(ByteBuffer.wrap(VIDEO));
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
