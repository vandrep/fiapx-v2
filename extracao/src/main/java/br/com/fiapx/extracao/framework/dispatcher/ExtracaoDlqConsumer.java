package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.framework.observabilidade.Rastro;
import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Consumidor da propria DLQ {@code extracao.extrair.dlq} (docs/contratos/mensagens.md §
 * Dead-letter queues) — sem ele o Video trava em PROCESSANDO para sempre. A mensagem aqui e
 * o mesmo {@code ExtrairVideo} original, redirecionado apos esgotar o {@code
 * x-delivery-limit=3}: nao ha o que reprocessar, so publicar a falha definitiva.
 *
 * <p>Recebe {@code Message}, e nao o payload cru, por causa do rastro (ticket 059): o contexto
 * de trace que veio no header AMQP so e alcancavel pelo {@link Message}, e sem ele a publicacao
 * de {@code ExtracaoFalhou} que este consumidor dispara abriria um rastro novo — o Video que
 * esgotou as tentativas ficaria com a travessia partida bem no ponto que interessa investigar.
 * {@link Acknowledgment.Strategy#MANUAL} e explicito para o ack nao depender do default do
 * SmallRye para assinaturas com {@code Message}; o nack continua passando pelo {@code
 * failure-strategy=reject} do canal, que e o que empurra a mensagem para o Estacionamento.
 */
@ApplicationScoped
public class ExtracaoDlqConsumer {

    private static final Logger LOG = Logger.getLogger(ExtracaoDlqConsumer.class);

    @Inject
    ExtracaoController extracaoController;

    @Inject
    Rastro rastro;

    @Incoming("extrair-video-dlq")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumir(Message<ExtrairVideo> mensagem) {
        ExtrairVideo comandoEsgotado = mensagem.getPayload();
        return rastro.naMensagem("extracao.tentativas-esgotadas", comandoEsgotado.idVideo(), mensagem, () -> {
                    // Dentro do rastro, e nao antes dele: e assim que este aviso — o unico que
                    // nomeia o Video preso — chega ao Loki pendurado no span do proprio Video.
                    // O log do conector (log.nackedIgnoreMessage) diz que um nack aconteceu no
                    // canal; o que operacao precisa para agir e qual Video ficou preso (ticket
                    // 029), e esse numero so esta na mensagem.
                    LOG.warnf("x-delivery-limit=3 esgotado para extracao.extrair, idVideo=%s",
                            comandoEsgotado.idVideo());
                    return Uni.createFrom().completionStage(extracaoController.processarTentativasEsgotadas(
                            comandoEsgotado.idVideo(), "x-delivery-limit=3 esgotado para extracao.extrair"));
                })
                .onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                        falha == null ? mensagem.ack() : mensagem.nack(falha)));
    }
}
