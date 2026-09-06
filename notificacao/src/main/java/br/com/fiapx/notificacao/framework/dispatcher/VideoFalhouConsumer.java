package br.com.fiapx.notificacao.framework.dispatcher;

import br.com.fiapx.notificacao.framework.observabilidade.Rastro;
import br.com.fiapx.notificacao.interfaces.controllers.NotificacaoController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

/**
 * Monta command e chama o controller, sem regra propria (docs/contratos/mensagens.md §
 * Camadas). Sem {@code @Blocking}: o envio de e-mail do quarkus-mailer e reativo ponta a
 * ponta, ao contrario do ffmpeg do `extracao`. {@code failure-strategy=requeue} no canal: uma
 * falha do SMTP vira <b>nack</b>, e o {@code x-delivery-limit=3} da fila quorum decide quando
 * esgotar para {@code notificacao.dlq} — terminal, sem consumidor (ADR 0001).
 *
 * <p>{@code donoSub} do contrato so serve para correlacionar este log com um chamado de
 * suporte — o `core` nao tem uso de negocio para ele (ver {@code
 * EnviarNotificacaoDeFalhaUseCase}).
 *
 * <p>Recebe {@code Message} por causa do rastro (ticket 059): e do header AMQP que vem o
 * contexto que liga este e-mail ao Video que o motivou, tres servicos atras. Este e o ultimo
 * salto da travessia — sem ele, o `notificacao` seria o unico dos tres a nao aparecer na busca
 * por {@code idVideo}. {@link Acknowledgment.Strategy#MANUAL} explicito para o ack nao depender
 * do default do SmallRye para assinaturas com {@code Message}; o nack continua passando pelo
 * {@code failure-strategy} do canal, sem mudanca de comportamento.
 */
@ApplicationScoped
public class VideoFalhouConsumer {

    private static final Logger LOG = Logger.getLogger(VideoFalhouConsumer.class);

    @Inject
    NotificacaoController notificacaoController;

    @Inject
    Rastro rastro;

    @Incoming("video-falhou")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumir(Message<VideoFalhou> mensagem) {
        var evento = mensagem.getPayload();
        return rastro.naMensagem("notificacao.video-falhou", evento.idVideo(), mensagem, () -> {
                    LOG.infof("notificando falha do video %s (dono=%s)", evento.idVideo(), evento.donoSub());
                    return Uni.createFrom().completionStage(notificacaoController.notificarFalha(
                            evento.idVideo(), evento.emailDono(), evento.nomeArquivoOriginal(),
                            evento.codigoMotivo(), evento.ocorridoEm()));
                })
                .onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                        falha == null ? mensagem.ack() : mensagem.nack(falha)));
    }
}
