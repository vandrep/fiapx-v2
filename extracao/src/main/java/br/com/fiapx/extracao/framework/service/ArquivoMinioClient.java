package br.com.fiapx.extracao.framework.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * As duas idas ao MinIO com a retentativa do ADR 0001, isoladas do
 * {@link ArquivoMinioAdapter}: aqui mora a chamada ao armazenamento e a politica de
 * reentrega dela, e la mora a traducao para o que o `core` pede.
 *
 * <h2>A retentativa e do Mutiny, e nao do {@code @Retry} (ticket 061)</h2>
 *
 * Ate o ticket 061 os dois metodos eram {@code @Retry} + {@code @AsynchronousNonBlocking} do
 * SmallRye Fault Tolerance. Isso <b>travava a Extracao para sempre</b>, em cerca de uma
 * mensagem a cada quinze, e o travamento foi medido, isolado e reproduzido — nao deduzido:
 *
 * <ol>
 * <li>numa operacao verdadeiramente assincrona, o interceptor do Fault Tolerance monta
 *     {@code RememberEventLoop -> ThreadOffload}. O {@code RememberEventLoop} le o contexto
 *     Vert.x corrente e o guarda no contexto da chamada; o {@code ThreadOffload}, vendo esse
 *     {@code Executor}, deixa de invocar o metodo na thread do chamador e o <b>reagenda no
 *     mesmo contexto Vert.x</b> em que a cadeia do consumidor ja esta rodando
 *     ({@code VertxExecutor} -> {@code runOnContext}/{@code executeBlocking});</li>
 * <li>esse reagendamento, as vezes, <b>nao roda nunca</b>. O corpo do metodo abaixo nem
 *     comeca, nenhuma thread trabalha, nenhum socket abre para o MinIO, nenhuma retentativa
 *     dispara e nada e logado. A mensagem fica sem ack, e com
 *     {@code max-outstanding-messages=1} a replica fica presa para sempre.</li>
 * </ol>
 *
 * <p>O que fica medido e (1) e (2); o passo que liga um ao outro e leitura, e a unica que
 * encaixa: na ordenacao daquele contexto a chamada entra atras da propria cadeia que espera
 * por ela — o consumidor {@code @Blocking} de {@code extracao.extrair} so termina quando este
 * download terminar. Nao da para observa-lo direto: qualquer log dentro dessa janela faz o
 * defeito sumir (90 ciclos limpos em tres variantes instrumentadas). Por isso a confirmacao
 * veio de A/B causal.
 *
 * <p>A medicao esta no ticket 061: com as anotacoes, 4 travamentos em ~60 ciclos; com a
 * retentativa do Mutiny, 0 em 90 ciclos, no mesmo host e com o mesmo roteiro
 * ({@code scripts/carga/travamento.sh}).
 *
 * <p>O que a troca preserva: mesma contagem (3 retentativas), mesma espera fixa (2 s), e
 * retentativa <b>so</b> em {@code Exception} — {@code Error} nao e retentado, como no
 * {@code @Retry}. O que ela remove e o desvio pelo contexto Vert.x: o {@code Uni} retenta na
 * propria cadeia, sem reagendar nada em fila de ninguem.
 *
 * <p>O motivo original de este bean ser separado do adapter — self-invocation nao dispara
 * interceptor de CDI — morreu junto com o interceptor. A separacao fica porque continua
 * valendo por si: o adapter conhece bucket e chave, este bean conhece a API S3 e quantas
 * vezes insistir nela.
 */
@ApplicationScoped
public class ArquivoMinioClient {

    /** Os dois numeros do {@code @Retry} que este bean tinha ate o ticket 061 (ADR 0001). */
    private static final int MAXIMO_DE_RETENTATIVAS = 3;
    private static final Duration ESPERA_ENTRE_TENTATIVAS = Duration.ofSeconds(2);

    @Inject
    S3AsyncClient s3;

    public CompletionStage<Path> baixar(String bucket, String chave, Path destino) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRetentativa(Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toFile(destino)))
                .map(resposta -> destino))
                .subscribeAsCompletionStage();
    }

    public CompletionStage<Void> gravar(String bucket, String chave, Path origem) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRetentativa(Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(origem)))
                .replaceWithVoid())
                .subscribeAsCompletionStage();
    }

    /**
     * A espera e fixa, e nao exponencial: {@code withBackOff(x, x)} com {@code jitter} zero e
     * como o Mutiny escreve "sempre 2 s". Um backoff crescente seria outra politica que a
     * ADR 0001 nao pediu, e mudar a politica no mesmo commit que corrige o travamento tiraria
     * o sentido da medicao.
     *
     * <p>O {@code completionStage} dos chamadores recebe um {@link java.util.function.Supplier},
     * e nao um estagio pronto: e o que faz cada resubscricao abrir uma requisicao S3 nova, do
     * mesmo jeito que o {@code @Retry} reinvocava o metodo inteiro.
     */
    private static <T> Uni<T> comRetentativa(Uni<T> chamada) {
        return chamada.onFailure(Exception.class::isInstance).retry()
                .withBackOff(ESPERA_ENTRE_TENTATIVAS, ESPERA_ENTRE_TENTATIVAS)
                .withJitter(0)
                .atMost(MAXIMO_DE_RETENTATIVAS);
    }
}
