package br.com.fiapx.videos.framework.service;

import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.reactivestreams.FlowAdapters;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * As duas chamadas ao MinIO que precisam de {@code @Retry} (ADR 0001), isoladas num bean
 * proprio — o mesmo desenho que o `extracao` e o `notificacao` ja usam, pelos mesmos dois
 * motivos medidos la:
 *
 * <ol>
 * <li>{@code @Retry} do SmallRye Fault Tolerance so reconhece um metodo como assincrono
 * quando ele declara {@code CompletionStage<T>} — {@code CompletableFuture<T>} nao conta,
 * mesmo sendo subtipo (a checagem e {@code CompletionStage.class.equals(returnType)},
 * exata).
 * <li>O metodo anotado nao pode ser chamado de dentro do proprio bean: self-invocation
 * ignora o proxy do CDI e o interceptor nunca dispara. Por isso este bean e separado do
 * {@link ArquivoMinioAdapter}, que o injeta e chama de fora.
 * </ol>
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

    @Inject
    S3AsyncClient s3;

    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @AsynchronousNonBlocking
    public CompletionStage<Void> gravar(String bucket, String chave, Path arquivo) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(arquivo)))
                .replaceWithVoid()
                .subscribeAsCompletionStage();
    }

    /**
     * O objeto ausente e traduzido para {@link Optional#empty()} <b>antes</b> de o
     * interceptor ver o resultado, e nao depois: assim a chave que nao existe completa a
     * chamada com sucesso e nao gasta tentativa nenhuma. Repetir tres vezes um
     * {@code NoSuchKey} seria segurar o 410 do contrato por segundos para um desfecho que ja
     * se sabe no primeiro erro. Qualquer outra falha continua falha, e e essa que o retry
     * cobre (ticket 019).
     */
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @AsynchronousNonBlocking
    public CompletionStage<Optional<Flow.Publisher<ByteBuffer>>> abrirSeExistir(String bucket, String chave) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toPublisher())
                        .<Optional<Flow.Publisher<ByteBuffer>>>thenApply(
                                publicador -> Optional.of(FlowAdapters.toFlowPublisher(publicador)))
                        .exceptionally(ArquivoMinioClient::vazioSeAusente))
                .subscribeAsCompletionStage();
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
