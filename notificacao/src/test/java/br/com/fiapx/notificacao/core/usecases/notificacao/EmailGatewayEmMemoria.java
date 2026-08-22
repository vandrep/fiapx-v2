package br.com.fiapx.notificacao.core.usecases.notificacao;

import br.com.fiapx.notificacao.core.interfaces.gateway.EmailGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Duble em memoria: nenhum SMTP real, so os e-mails "enviados" ficam registrados. */
final class EmailGatewayEmMemoria implements EmailGateway {

    final List<Enviado> enviados = new ArrayList<>();
    RuntimeException falha;

    @Override
    public CompletableFuture<Void> enviar(String destinatario, String assunto, String corpo) {
        if (falha != null) {
            return CompletableFuture.failedFuture(falha);
        }
        enviados.add(new Enviado(destinatario, assunto, corpo));
        return CompletableFuture.completedFuture(null);
    }

    record Enviado(String destinatario, String assunto, String corpo) {
    }
}
