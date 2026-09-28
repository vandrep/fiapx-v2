package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.framework.observabilidade.MetricaDeVideosPresos;
import br.com.fiapx.videos.interfaces.controllers.VideosPresosController;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Amostra a contagem de Videos presos para a {@link MetricaDeVideosPresos} (ticket 106).
 *
 * <p>Um minuto, que e o intervalo de exportacao de metrica do Quarkus e o de avaliacao do
 * Grafana: amostrar mais rapido so geraria consulta que nenhum dos dois le. Contra um limiar de
 * 30 min, um minuto de atraso na deteccao nao muda nada.
 *
 * <p>Retorna {@code Uni} pelo mesmo motivo do {@link ReconciliacaoScheduler}: o Hibernate
 * Reactive exige o event loop. Falha na contagem apaga a amostra em vez de manter a anterior —
 * um numero velho exportado como atual e pior que a serie sumir.
 */
@ApplicationScoped
public class VideosPresosScheduler {

    private static final Logger LOG = Logger.getLogger(VideosPresosScheduler.class);

    @Inject
    VideosPresosController videosPresosController;

    @Inject
    MetricaDeVideosPresos metricaDeVideosPresos;

    @Scheduled(every = "1m")
    Uni<Void> amostrar() {
        return Uni.createFrom().completionStage(videosPresosController::contar)
                .invoke(metricaDeVideosPresos::registrarAmostra)
                .onFailure().invoke(falha -> {
                    metricaDeVideosPresos.descartarAmostra();
                    LOG.warnf(falha, "contagem de Vídeos presos falhou; a métrica fica sem amostra");
                })
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }
}
