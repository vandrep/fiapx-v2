package br.com.fiapx.notificacao.framework.service;

import br.com.fiapx.notificacao.core.interfaces.gateway.EmailGateway;
import io.quarkus.mailer.Mail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * O SMTP por quarkus-mailer, visto pelo `core`. Fora de %prod o envio e capturado por
 * {@code MockMailbox} em vez de sair de verdade (mock automatico do quarkus-mailer) — sem
 * isso o teste precisaria de um Dev Service de MailHog.
 */
@ApplicationScoped
public class MailerEmailAdapter implements EmailGateway {

    @Inject
    MailerEmailClient mailerEmailClient;

    @Override
    public CompletableFuture<Void> enviar(String destinatario, String assunto, String corpo) {
        return mailerEmailClient.enviar(Mail.withText(destinatario, assunto, corpo)).toCompletableFuture();
    }
}
