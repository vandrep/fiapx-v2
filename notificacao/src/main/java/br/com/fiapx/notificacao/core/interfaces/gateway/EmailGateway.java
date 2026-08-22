package br.com.fiapx.notificacao.core.interfaces.gateway;

import java.util.concurrent.CompletableFuture;

/** O SMTP visto pelo dominio: so envia, sem saber MailHog, Vert.x Mail Client ou retry. */
public interface EmailGateway {

    CompletableFuture<Void> enviar(String destinatario, String assunto, String corpo);
}
