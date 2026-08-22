package br.com.fiapx.notificacao.framework.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletionStage;

/**
 * O unico envio de SMTP que precisa de {@code @Retry} (ADR 0001), isolado num bean proprio
 * pelos mesmos dois motivos medidos no {@code ArquivoMinioClient} do `extracao`: {@code
 * @Retry} do SmallRye Fault Tolerance so reconhece {@code CompletionStage<T>} exato como
 * assincrono, e o metodo anotado nao pode ser chamado de dentro do proprio bean (self-
 * invocation ignora o proxy do CDI).
 */
@ApplicationScoped
public class MailerEmailClient {

    @Inject
    ReactiveMailer mailer;

    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @AsynchronousNonBlocking
    public CompletionStage<Void> enviar(Mail mail) {
        return mailer.send(mail).subscribeAsCompletionStage();
    }
}
