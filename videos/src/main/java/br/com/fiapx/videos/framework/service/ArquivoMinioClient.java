package br.com.fiapx.videos.framework.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.reactivestreams.FlowAdapters;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * As duas chamadas ao MinIO com a retentativa do ADR 0001, isoladas num bean proprio — o
 * mesmo desenho que o `extracao` e o `notificacao` usam.
 *
 * <p>A retentativa e do Mutiny, e nao do {@code @Retry} do SmallRye Fault Tolerance: o
 * ticket 061 mediu, no `extracao`, que o interceptor reagenda a chamada no contexto Vert.x
 * do chamador e pode prende-la la para sempre — nenhuma thread, nenhum socket, nenhuma
 * linha de log. O `videos` chama daqui a borda HTTP, que roda no mesmo tipo de contexto, e
 * a construcao era a mesma; a explicacao completa do mecanismo esta no
 * {@code ArquivoMinioClient} do `extracao`, que e onde ele foi reproduzido.
 *
 * <p>Aqui a protecao pesa mais do que nos dois workers: atras da borda sincrona nao ha fila
 * quorum nenhuma para reentregar: o blip que nao for absorvido neste ponto ja saiu como 500
 * para quem chamou a API (ticket 048).
 *
 * <p>Chave e bucket chegam prontos: a convencao de nomes continua sendo assunto <b>so</b> do
 * {@link ArquivoMinioAdapter} (ticket 011). O que este bean traduz e a API S3 — o objeto que
 * nao esta la vira {@link Optional#empty()} —, e nao o significado disso na borda: quem
 * decide que ausencia e {@code 410} continua sendo o caso de uso.
 */
@ApplicationScoped
public class ArquivoMinioClient {

    private static final int MAXIMO_DE_RETENTATIVAS = 3;
    private static final Duration ESPERA_ENTRE_TENTATIVAS = Duration.ofSeconds(2);

    @Inject
    S3AsyncClient s3;

    public CompletionStage<Void> gravar(String bucket, String chave, Path arquivo) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRetentativa(Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(arquivo)))
                .replaceWithVoid())
                .subscribeAsCompletionStage();
    }

    /**
     * O objeto ausente e traduzido para {@link Optional#empty()} <b>dentro</b> da chamada, e
     * nao depois da retentativa: assim a chave que nao existe completa a chamada com sucesso
     * e nao gasta tentativa nenhuma. Repetir tres vezes um
     * {@code NoSuchKey} seria segurar o 410 do contrato por segundos para um desfecho que ja
     * se sabe no primeiro erro. Qualquer outra falha continua falha, e e essa que o retry
     * cobre (ticket 019).
     */
    public CompletionStage<Optional<Flow.Publisher<ByteBuffer>>> abrirSeExistir(String bucket, String chave) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRetentativa(Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toPublisher())
                        .<Optional<Flow.Publisher<ByteBuffer>>>thenApply(
                                publicador -> Optional.of(FlowAdapters.toFlowPublisher(publicador)))
                        .exceptionally(ArquivoMinioClient::vazioSeAusente)))
                .subscribeAsCompletionStage();
    }

    /**
     * Os mesmos dois numeros que o {@code @Retry} declarava (ADR 0001): tres retentativas,
     * espera fixa de 2 s. {@code withBackOff(x, x)} com {@code jitter} zero e como o Mutiny
     * escreve espera fixa, e a retentativa cobre {@code Exception}, nao {@code Error} — o
     * mesmo recorte do {@code @Retry}.
     */
    private static <T> Uni<T> comRetentativa(Uni<T> chamada) {
        return chamada.onFailure(Exception.class::isInstance).retry()
                .withBackOff(ESPERA_ENTRE_TENTATIVAS, ESPERA_ENTRE_TENTATIVAS)
                .withJitter(0)
                .atMost(MAXIMO_DE_RETENTATIVAS);
    }

    /**
     * <b>So</b> o objeto ausente vira Optional vazio. Mapear qualquer falha do MinIO para
     * "expirou" faria um MinIO fora do ar mandar o cliente desistir para sempre; erro de
     * infraestrutura tem de continuar 500 (ticket 019).
     */
    private static Optional<Flow.Publisher<ByteBuffer>> vazioSeAusente(Throwable falha) {
        var causa = falha instanceof CompletionException ? falha.getCause() : falha;
        if (causa instanceof NoSuchKeyException) {
            return Optional.empty();
        }
        throw causa instanceof RuntimeException erro ? erro : new CompletionException(causa);
    }
}
