package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.interfaces.controllers.ReconciliacaoController;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Gatilho burro da varredura de reconciliacao (ADR 0003): monta nada, so chama o controller.
 * Sem {@code SKIP LOCKED} nem eleicao de lider — duas replicas varrendo ao mesmo tempo e
 * aceito, o consumo rio abaixo e idempotente.
 *
 * <p>Retorna {@code Uni}, nao {@code void}: assim o scheduler despacha o metodo <b>no event
 * loop</b>, que e onde o Hibernate Reactive exige rodar — despachado no worker pool (o
 * default de um metodo {@code void}), a primeira chamada ao Panache falha com
 * "should exclusively be invoked from a Vert.x EventLoop thread".
 */
@ApplicationScoped
public class ReconciliacaoScheduler {

    private static final Logger LOG = Logger.getLogger(ReconciliacaoScheduler.class);

    @Inject
    ReconciliacaoController reconciliacaoController;

    /**
     * So registra passada que republicou alguma coisa — a passada vazia e o caso normal, a
     * cada 30 s, e logar isso afogaria o log. Republicar, ao contrario, e o mecanismo do
     * ADR 0003 agindo, e ate o ticket 027 nao havia como observa-lo: a garantia estava
     * escrita e nao verificada porque nada a tornava visivel.
     */
    @Scheduled(every = "30s")
    Uni<Void> reconciliar() {
        return Uni.createFrom().completionStage(reconciliacaoController.reconciliar())
                .invoke(republicacoes -> {
                    if (republicacoes.houveAlgo()) {
                        LOG.infof("reconciliacao republicou %d comando(s) e %d falha(s)",
                                republicacoes.comandos(), republicacoes.falhas());
                    }
                })
                .replaceWithVoid();
    }
}
