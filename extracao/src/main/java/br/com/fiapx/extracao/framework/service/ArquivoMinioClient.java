package br.com.fiapx.extracao.framework.service;

import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletionStage;

/**
 * As duas chamadas ao MinIO que precisam de {@code @Retry} (ADR 0001), isoladas num bean
 * proprio. Dois achados reais desta implementacao, verificados com um teste descartavel
 * antes de escrever este codigo:
 *
 * <ol>
 * <li>{@code @Retry} do SmallRye Fault Tolerance so reconhece um metodo como assincrono
 * quando ele declara {@code CompletionStage<T>} — {@code CompletableFuture<T>} nao conta,
 * mesmo sendo subtipo (a checagem e {@code CompletionStage.class.equals(returnType)},
 * exata). Por isso este bean devolve {@code CompletionStage}, nao {@code CompletableFuture}
 * como o resto do `core` exige (ArchitectureConstraintsTest).
 * <li>O metodo anotado nao pode ser chamado de dentro do proprio bean: self-invocation
 * ignora o proxy do CDI e o interceptor nunca dispara. Por isso este bean e separado do
 * {@link ArquivoMinioAdapter}, que o injeta e chama de fora.
 * </ol>
 */
@ApplicationScoped
public class ArquivoMinioClient {

    @Inject
    S3AsyncClient s3;

    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @AsynchronousNonBlocking
    public CompletionStage<Path> baixar(String bucket, String chave, Path destino) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toFile(destino)))
                .map(resposta -> destino)
                .subscribeAsCompletionStage();
    }

    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @AsynchronousNonBlocking
    public CompletionStage<Void> gravar(String bucket, String chave, Path origem) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(origem)))
                .replaceWithVoid()
                .subscribeAsCompletionStage();
    }
}
