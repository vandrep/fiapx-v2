package br.com.fiapx.notificacao.framework.dispatcher;

import br.com.fiapx.notificacao.interfaces.controllers.NotificacaoController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
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
 */
@ApplicationScoped
public class VideoFalhouConsumer {

    private static final Logger LOG = Logger.getLogger(VideoFalhouConsumer.class);

    @Inject
    NotificacaoController notificacaoController;

    @Incoming("video-falhou")
    public Uni<Void> consumir(VideoFalhou evento) {
        LOG.infof("notificando falha do video %s (dono=%s)", evento.idVideo(), evento.donoSub());
        return Uni.createFrom().completionStage(notificacaoController.notificarFalha(
                evento.idVideo(), evento.emailDono(), evento.nomeArquivoOriginal(),
                evento.codigoMotivo(), evento.ocorridoEm()));
    }
}
