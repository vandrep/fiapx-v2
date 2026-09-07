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
 * <p>Os numeros nao mudaram: tres retentativas, espera fixa de 2 s, e so sobre
 * {@code Exception}.
 */
@ApplicationScoped
public class MailerEmailClient {

    private static final int MAXIMO_DE_RETENTATIVAS = 3;
    private static final Duration ESPERA_ENTRE_TENTATIVAS = Duration.ofSeconds(2);

    @Inject
    ReactiveMailer mailer;

    public CompletionStage<Void> enviar(Mail mail) {
        return Uni.createFrom().deferred(() -> mailer.send(mail))
                .onFailure(Exception.class::isInstance).retry()
                .withBackOff(ESPERA_ENTRE_TENTATIVAS, ESPERA_ENTRE_TENTATIVAS)
                .withJitter(0)
                .atMost(MAXIMO_DE_RETENTATIVAS)
                .subscribeAsCompletionStage();
    }
}
