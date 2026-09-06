package br.com.fiapx.notificacao.framework.service;

import br.com.fiapx.notificacao.core.interfaces.gateway.EmailGateway;
import br.com.fiapx.notificacao.framework.observabilidade.Rastro;
import io.quarkus.mailer.Mail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * O SMTP por quarkus-mailer, visto pelo `core`. Fora de %prod o envio e capturado por
 * {@code MockMailbox} em vez de sair de verdade (mock automatico do quarkus-mailer) — sem
 * isso o teste precisaria de um Dev Service de MailHog.
 *
 * <p>O envio ganha span (ticket 059): e o ultimo trecho da travessia de um Video que falhou, e
 * o unico que sai do sistema por um protocolo que nenhuma auto-instrumentacao daqui cobre. Um
 * SMTP fora do ar aparece como este span em erro, no rastro do proprio Video.
 */
@ApplicationScoped
public class MailerEmailAdapter implements EmailGateway {

    @Inject
    MailerEmailClient mailerEmailClient;

    @Inject
    Rastro rastro;

    @Override
    public CompletableFuture<Void> enviar(String destinatario, String assunto, String corpo) {
        return rastro.emTorno("notificacao.enviar-email",
                () -> mailerEmailClient.enviar(Mail.withText(destinatario, assunto, corpo)).toCompletableFuture());
    }
}
