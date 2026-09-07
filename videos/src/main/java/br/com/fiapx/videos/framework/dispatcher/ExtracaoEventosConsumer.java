package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.framework.observabilidade.Rastro;
import br.com.fiapx.videos.interfaces.controllers.ExtracaoEventosController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import static br.com.fiapx.videos.framework.dispatcher.AckManual.comAckManual;

/**
 * Monta command e chama o controller, sem regra propria (docs/contratos/mensagens.md §
 * Camadas). {@code failure-strategy=requeue} nos tres canais: {@code fail} derrubaria o
 * health check (ADR 0001).
 *
 * <p>Os tres metodos recebem {@code Message}, e nao o payload cru, por causa do rastro (ticket
 * 059): o contexto de trace chega no header AMQP e so e alcancavel pelo {@link Message}. Sem
 * ele, o {@code VideoFalhou} que o caminho de falha publica abriria um rastro novo, e o
 * `notificacao` apareceria desligado do Video que o motivou — justamente o salto que faz a
 * travessia atravessar os tres servicos.
 *
 * <p>{@link Acknowledgment.Strategy#MANUAL} explicito nos tres, e ack/nack a mao: a assinatura
 * mudou, e deixar o ack no default seria confiar num default que a assinatura pode mudar por
 * baixo. O comportamento observavel nao muda — o nack continua caindo no {@code
 * failure-strategy=requeue} do canal, e o {@code x-delivery-limit=3} da fila quorum continua
 * decidindo quando esgotar para {@code videos.dlq}.
 */
@ApplicationScoped
public class ExtracaoEventosConsumer {

    private static final Logger LOG = Logger.getLogger(ExtracaoEventosConsumer.class);

    @Inject
    ExtracaoEventosController extracaoEventosController;

    @Inject
    Rastro rastro;

    @Incoming("extracao-iniciada")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumirIniciada(Message<ExtracaoIniciada> mensagem) {
        var evento = mensagem.getPayload();
        return comAckManual(mensagem, rastro.naMensagem("videos.extracao-iniciada", evento.idVideo(), mensagem,
                () -> Uni.createFrom().completionStage(
                        extracaoEventosController.processarIniciada(evento.idVideo()))));
    }

    @Incoming("extracao-concluida")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumirConcluida(Message<ExtracaoConcluida> mensagem) {
        var evento = mensagem.getPayload();
        var resultado = new ResultadoExtracao(
                evento.concluidaEm(), evento.chavePacote(), evento.quantidadeFrames(), evento.tamanhoBytes());
        return comAckManual(mensagem, rastro.naMensagem("videos.extracao-concluida", evento.idVideo(), mensagem,
                () -> Uni.createFrom().completionStage(
                        extracaoEventosController.processarConcluida(evento.idVideo(), resultado))));
    }

    @Incoming("extracao-falhou")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumirFalhou(Message<ExtracaoFalhou> mensagem) {
        var evento = mensagem.getPayload();
        return comAckManual(mensagem, rastro.naMensagem("videos.extracao-falhou", evento.idVideo(), mensagem, () -> {
            // Dentro do rastro: e o unico log do `videos` que carrega o detalhe tecnico da
            // falha, e e nele que a investigacao por idVideo desemboca.
            LOG.warnf("Extração falhou: idVideo=%s codigoMotivo=%s detalheTecnico=%s",
                    evento.idVideo(), evento.codigoMotivo(), evento.detalheTecnico());
            return Uni.createFrom().completionStage(extracaoEventosController.processarFalhou(
                    evento.idVideo(), evento.codigoMotivo(), evento.ocorridoEm()));
        }));
    }
}
