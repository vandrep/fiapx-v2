package br.com.fiapx.notificacao.framework.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * O unico envio de SMTP com retentativa (ADR 0001), isolado num bean proprio junto da
 * politica que o protege — o mesmo desenho do {@code ArquivoMinioClient} do `extracao`.
 *
 * <p>A retentativa e do Mutiny, e nao do {@code @Retry} do SmallRye Fault Tolerance. O
 * ticket 061 mediu, no `extracao`, que o interceptor reagenda a chamada no contexto Vert.x
 * do chamador e pode prende-la la para sempre; aqui o chamador tambem e um consumidor
 * {@code @Blocking}, que e exatamente a forma em que o travamento foi reproduzido. O
 * mecanismo esta explicado no {@code ArquivoMinioClient} do `extracao`.
 *
 * <p>Os numeros estao em {@link #comRepeticao}; so a espera sai de configuracao
 * ({@link #esperaEntreRepeticoes}, ticket 085).
 */
@ApplicationScoped
public class MailerEmailClient {

    /**
     * Duas repeticoes depois da primeira chamada, que sao as 3 chamadas ao recurso do
     * ADR 0001 — a aritmetica esta em {@link #comRepeticao} (ticket 086).
     */
    private static final int MAXIMO_DE_REPETICOES = 2;
    /**
     * Fracao da espera, e nao valor absoluto: o Mutiny pede fracao. Os 10% vieram dos 200 ms de
     * jitter que o {@code @Retry} do MicroProfile trazia por default sobre 2 s, e continuam
     * amarrados a {@link #esperaEntreRepeticoes} — sobre a espera de producao dao os mesmos
     * 200 ms; sobre uma espera reduzida dao proporcionalmente menos.
     */
    private static final double JITTER = 0.1;

    @Inject
    ReactiveMailer mailer;

    /**
     * Os 2 s do ADR 0001 em producao, no mesmo desenho que o `videos` estreou no ticket 080. E
     * configuracao <b>deste bean</b>, no namespace {@code fiapx.} do projeto — nao chave de
     * tolerancia a falhas por interceptor, que a setima regra do teste arquitetural proibe desde o
     * ticket 064. O default vive aqui, e nao no {@code .properties}, para que a espera de producao
     * sobreviva a um arquivo de configuracao incompleto.
     *
     * <p>O prefixo e o do servico, e nao o do recurso: {@code fiapx.armazenamento.} existe porque
     * o MinIO e compartilhado entre `videos` e `extracao`, e o SMTP so o `notificacao` alcanca —
     * entao a chave mora onde as outras chaves de um servico so moram ({@code fiapx.extracao.*}).
     *
     * <p>Nao ha {@code %test.} para esta chave no {@code application.properties}, e a diferenca e
     * do teste e nao da copia: quem paga a espera aqui e o {@code RepeticaoNoSmtpTest}, que monta
     * o bean a mao e por isso atribui o campo direto. Nenhum {@code @QuarkusTest} deste servico
     * injeta blip no SMTP, entao um override de perfil nao teria leitor (ticket 085).
     */
    @ConfigProperty(name = "fiapx.notificacao.espera-entre-repeticoes", defaultValue = "2s")
    Duration esperaEntreRepeticoes;

    public CompletionStage<Void> enviar(Mail mail) {
        return comRepeticao(Uni.createFrom().deferred(() -> mailer.send(mail)))
                .subscribeAsCompletionStage();
    }

    /**
     * A repeticao do ADR 0001: <b>3 chamadas ao recurso — a primeira mais 2 repeticoes</b>,
     * 2 s de espera, jitter de 10% (que e os 200 ms sobre 2 s do default do MicroProfile) e so
     * sobre {@code Exception} — {@code Error} nao e repetido. {@code atMost(n)} do Mutiny conta
     * as repeticoes <i>depois</i> da primeira chamada, entao 3 chamadas se escrevem
     * {@code atMost(2)}. {@code withBackOff(x, x)} e como o Mutiny escreve espera constante;
     * backoff crescente seria outra politica, que o ADR nao pediu.
     *
     * <p>A aritmetica e uma so e mora no ADR 0001, nao aqui: os quatro lugares que a
     * implementam — as tres copias de {@code comRepeticao} e o {@code PostgresRetry} do
     * `videos` — citam a mesma. Ate o ticket 086 esta copia fazia 4 chamadas, herdadas numero
     * por numero do {@code @Retry(maxRetries=3)} que o ticket 061 removeu, enquanto o
     * {@code PostgresRetry} fazia 3: o ADR dizia "tres tentativas" e as duas leituras estavam
     * implementadas. O 086 escolheu 3 chamadas; o motivo — a quarta segura o chamador por mais
     * 2 s sem comprar blip que ainda valha a pena chamar de transitorio — esta escrito la.
     *
     * <p>A contagem continua fixa e a espera virou {@link #esperaEntreRepeticoes}: o numero de
     * chamadas e a politica, e a espera e o preco dela.
     *
     * <p><b>Repeticao, e nao "tentativa".</b> No {@code CONTEXT.md} tentativa e uma <i>entrega</i>
     * da mensagem ao worker, e o limite dela e 3 — estas 3 aqui sao chamadas de I/O dentro de
     * <b>uma</b> tentativa. Desde o 086 o ADR 0001 nao gasta mais a mesma palavra nas duas
     * contagens.
     *
     * <p>O que <b>nao</b> veio junto do {@code @Retry}: o {@code maxDuration} de 3 min. Ele
     * nunca chegou a limitar nada — as repeticoes de 2 s ficam duas ordens de grandeza abaixo —,
     * e o caso que ele parecia cobrir, a chamada que nao volta, ele nao cobria: era o
     * travamento do ticket 061.
     */
    private <T> Uni<T> comRepeticao(Uni<T> chamada) {
        return chamada.onFailure(Exception.class::isInstance).retry()
                .withBackOff(esperaEntreRepeticoes, esperaEntreRepeticoes)
                .withJitter(JITTER)
                .atMost(MAXIMO_DE_REPETICOES);
    }
}
