package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Consumidor da propria DLQ {@code extracao.extrair.dlq} (docs/contratos/mensagens.md §
 * Dead-letter queues) — sem ele o Video trava em PROCESSANDO para sempre. A mensagem aqui e
 * o mesmo {@code ExtrairVideo} original, redirecionado apos esgotar o {@code
 * x-delivery-limit=3}: nao ha o que reprocessar, so publicar a falha definitiva.
 */
@ApplicationScoped
public class ExtracaoDlqConsumer {

    private static final Logger LOG = Logger.getLogger(ExtracaoDlqConsumer.class);

    @Inject
    ExtracaoController extracaoController;

    @Incoming("extrair-video-dlq")
    public Uni<Void> consumir(ExtrairVideo comandoEsgotado) {
        // O log do conector (log.nackedIgnoreMessage) diz que um nack aconteceu no canal; o
        // que operacao precisa para agir e qual Video ficou preso (ticket 029) — esse numero
        // so esta na mensagem, nao no log do conector.
        LOG.warnf("x-delivery-limit=3 esgotado para extracao.extrair, idVideo=%s",
                comandoEsgotado.idVideo());
        return Uni.createFrom().completionStage(extracaoController.processarTentativasEsgotadas(
                comandoEsgotado.idVideo(), "x-delivery-limit=3 esgotado para extracao.extrair"));
    }
}
