package br.com.fiapx.extracao.framework.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 * <p>O que a troca preserva esta em {@link #comRepeticao}, numero por numero. O que ela
 * remove e o desvio pelo contexto Vert.x: o {@code Uni} repete na propria cadeia, sem
 * reagendar nada em fila de ninguem.
 *
 * <p>Um limite herdado, e que continua igual: em {@link #baixar}, cada resubscricao refaz o
 * {@code AsyncResponseTransformer.toFile(destino)}, que recusa arquivo existente. Um blip que
 * chegue <b>depois</b> de a escrita comecar queima as repeticoes com
 * {@code FileAlreadyExistsException} em vez de baixar de novo. O {@code @Retry} reinvocava o
 * metodo inteiro e fazia exatamente o mesmo; nao e regressao desta mudanca, e nao foi
 * corrigido aqui para nao misturar duas coisas no mesmo commit.
 *
 * <p>O motivo original de este bean ser separado do adapter — self-invocation nao dispara
 * interceptor de CDI — morreu junto com o interceptor. A separacao fica porque continua
 * valendo por si: o adapter conhece bucket e chave, este bean conhece a API S3 e quantas
 * vezes insistir nela.
 */
@ApplicationScoped
public class ArquivoMinioClient {

    private static final int MAXIMO_DE_REPETICOES = 3;
    /**
     * Fracao da espera, e nao valor absoluto: o Mutiny pede fracao. Os 10% vieram dos 200 ms de
     * jitter que o {@code @Retry} do MicroProfile trazia por default sobre 2 s, e continuam
     * amarrados a {@link #esperaEntreRepeticoes} — sobre a espera de producao dao os mesmos
     * 200 ms; sobre uma espera reduzida dao proporcionalmente menos.
     */
    private static final double JITTER = 0.1;

    @Inject
    S3AsyncClient s3;

    /**
     * Os 2 s do ADR 0001 em producao, no mesmo desenho que o `videos` estreou no ticket 080. E
     * configuracao <b>deste bean</b>, no namespace {@code fiapx.} do projeto — nao chave de
     * tolerancia a falhas por interceptor, que a setima regra do teste arquitetural proibe desde o
     * ticket 064. O default vive aqui, e nao no {@code .properties}, para que a espera de producao
     * sobreviva a um arquivo de configuracao incompleto.
     *
     * <p>Nao ha {@code %test.} para esta chave no {@code application.properties}, e a diferenca e
     * do teste e nao da copia: quem paga a espera aqui e o {@code RepeticaoNoMinioTest}, que monta
     * o bean a mao e por isso atribui o campo direto. Nenhum {@code @QuarkusTest} deste servico
     * injeta blip no MinIO, entao um override de perfil nao teria leitor (ticket 085).
     */
    @ConfigProperty(name = "fiapx.armazenamento.espera-entre-repeticoes", defaultValue = "2s")
    Duration esperaEntreRepeticoes;

    public CompletionStage<Path> baixar(String bucket, String chave, Path destino) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRepeticao(Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toFile(destino)))
                .map(resposta -> destino))
                .subscribeAsCompletionStage();
    }

    public CompletionStage<Void> gravar(String bucket, String chave, Path origem) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRepeticao(Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(origem)))
                .replaceWithVoid())
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
}
