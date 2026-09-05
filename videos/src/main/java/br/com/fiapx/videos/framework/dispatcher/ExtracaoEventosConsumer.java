package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.interfaces.controllers.ExtracaoEventosController;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * Monta command e chama o controller, sem regra propria (docs/contratos/mensagens.md §
 * Camadas). {@code failure-strategy=requeue} nos tres canais: {@code fail} derrubaria o
 * health check (ADR 0001).
 */
@ApplicationScoped
public class ExtracaoEventosConsumer {

    private static final Logger LOG = Logger.getLogger(ExtracaoEventosConsumer.class);

    @Inject
    ExtracaoEventosController extracaoEventosController;

    @Incoming("extracao-iniciada")
    public Uni<Void> consumirIniciada(ExtracaoIniciada evento) {
        return Uni.createFrom().completionStage(
                extracaoEventosController.processarIniciada(evento.idVideo()));
    }

    @Incoming("extracao-concluida")
    public Uni<Void> consumirConcluida(ExtracaoConcluida evento) {
        return Uni.createFrom().completionStage(extracaoEventosController.processarConcluida(
                evento.idVideo(),
                evento.chavePacote(),
                evento.quantidadeFrames(),
                evento.tamanhoBytes(),
                evento.concluidaEm()));
    }

    @Incoming("extracao-falhou")
    public Uni<Void> consumirFalhou(ExtracaoFalhou evento) {
        LOG.warnf("Extração falhou: idVideo=%s codigoMotivo=%s detalheTecnico=%s",
                evento.idVideo(), evento.codigoMotivo(), evento.detalheTecnico());
        return Uni.createFrom().completionStage(
                extracaoEventosController.processarFalhou(evento.idVideo(), evento.codigoMotivo(), evento.ocorridoEm()));
    }
}
