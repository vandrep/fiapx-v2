package br.com.fiapx.videos.framework.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

    private static final int MAXIMO_DE_REPETICOES = 3;
    /** Os 200 ms do default do {@code @Retry}, sobre os 2 s de espera: o Mutiny pede fracao. */
    private static final double JITTER = 0.1;

    @Inject
    S3AsyncClient s3;

    /**
     * Os 2 s do ADR 0001 em producao, reduzidos ao piso so no perfil de teste. E configuracao <b>deste
     * bean</b>, no namespace {@code fiapx.} do projeto — nao chave de tolerancia a falhas por
     * interceptor, que a setima regra do teste arquitetural proibe desde o ticket 064.
     *
     * <p>O que o cenario do blip julga e a repeticao <i>acontecer</i>, nao quanto ela espera
     * (ticket 048): com a espera de producao, os quatro cenarios do
     * {@code EnvioResisteABlipDoArmazenamentoTest} ficavam 20 s parados. O default de 2 s vive
     * aqui, e nao no {@code .properties}, para que a espera de producao sobreviva a um arquivo
     * de configuracao incompleto (ticket 080).
     */
    @ConfigProperty(name = "fiapx.armazenamento.espera-entre-repeticoes", defaultValue = "2s")
    Duration esperaEntreRepeticoes;

    public CompletionStage<Void> gravar(String bucket, String chave, Path arquivo) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRepeticao(Uni.createFrom()
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
        return comRepeticao(Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toPublisher())
                        .<Optional<Flow.Publisher<ByteBuffer>>>thenApply(
                                publicador -> Optional.of(FlowAdapters.toFlowPublisher(publicador)))
                        .exceptionally(ArquivoMinioClient::vazioSeAusente)))
                .subscribeAsCompletionStage();
    }

    /**
     * A repeticao do ADR 0001, com os mesmos numeros que o {@code @Retry} tinha ate o
     * ticket 061: 3 repeticoes, 2 s de espera, jitter de 10% (que e os 200 ms sobre 2 s do
     * default do MicroProfile) e so sobre {@code Exception} — {@code Error} nao e repetido.
     * {@code withBackOff(x, x)} e como o Mutiny escreve espera constante; backoff crescente
     * seria outra politica, que o ADR nao pediu.
     *
     * <p>A contagem continua fixa e a espera virou {@link #esperaEntreRepeticoes}: o numero de
     * repeticoes e a politica, e a espera e o preco dela.
     *
     * <p><b>Repeticao, e nao "tentativa".</b> No {@code CONTEXT.md} tentativa e uma <i>entrega</i>
     * da mensagem ao worker, e o limite dela tambem e 3 — os dois numeros coincidirem torna a
     * confusao facil. Estes 3 aqui sao repeticoes de uma chamada de I/O dentro de <b>uma</b>
     * tentativa.
     *
     * <p>O que <b>nao</b> veio junto: o {@code maxDuration} de 3 min do {@code @Retry}. Ele
     * nunca chegou a limitar nada — 3 repeticoes de 2 s ficam duas ordens de grandeza abaixo —,
     * e o caso que ele parecia cobrir, a chamada que nao volta, ele nao cobria: era o
     * travamento deste ticket.
     */
    private <T> Uni<T> comRepeticao(Uni<T> chamada) {
        return chamada.onFailure(Exception.class::isInstance).retry()
                .withBackOff(esperaEntreRepeticoes, esperaEntreRepeticoes)
                .withJitter(JITTER)
                .atMost(MAXIMO_DE_REPETICOES);
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
