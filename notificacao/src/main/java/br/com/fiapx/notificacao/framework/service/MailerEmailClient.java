package br.com.fiapx.notificacao.framework.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 * <p>Os numeros nao mudaram, e estao em {@link #comRepeticao}.
 */
@ApplicationScoped
public class MailerEmailClient {

    private static final int MAXIMO_DE_REPETICOES = 3;
    private static final Duration ESPERA_ENTRE_REPETICOES = Duration.ofSeconds(2);
    /** Os 200 ms do default do {@code @Retry}, sobre os 2 s de espera: o Mutiny pede fracao. */
    private static final double JITTER = 0.1;

    @Inject
    ReactiveMailer mailer;

    public CompletionStage<Void> enviar(Mail mail) {
        return comRepeticao(Uni.createFrom().deferred(() -> mailer.send(mail)))
                .subscribeAsCompletionStage();
    }

    /**
     * A repeticao do ADR 0001, com os mesmos numeros que o {@code @Retry} tinha ate o
     * ticket 061: 3 repeticoes, 2 s de espera, jitter de 10% (que e os 200 ms sobre 2 s do
     * default do MicroProfile) e so sobre {@code Exception} — {@code Error} nao e repetido.
     * {@code withBackOff(x, x)} e como o Mutiny escreve espera constante; backoff crescente
     * seria outra politica, que o ADR nao pediu.
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
    private static <T> Uni<T> comRepeticao(Uni<T> chamada) {
        return chamada.onFailure(Exception.class::isInstance).retry()
                .withBackOff(ESPERA_ENTRE_REPETICOES, ESPERA_ENTRE_REPETICOES)
                .withJitter(JITTER)
                .atMost(MAXIMO_DE_REPETICOES);
    }
}
