package br.com.fiapx.videos.framework.observabilidade;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.usecases.video.ContarVideosPresosUseCase.VideosPresos;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A segunda metrica propria do sistema (ticket 106): quantos Videos estao presos, por estado.
 *
 * <h2>Por que ela passou pela regra que recusou "Videos por estado"</h2>
 *
 * O ADR 0004 recusou um gauge de Videos por estado com dois argumentos: o endpoint de listagem
 * ja responde, e o gauge exigiria varredura periodica no Postgres para um numero que ninguem
 * consulta em regime. Os dois nao valem aqui. A listagem e <b>por Dono</b>, e nenhum endpoint
 * responde "ha Video preso de alguem"; e quem consulta este numero em regime e um alerta, a cada
 * minuto. Um Video pode ficar sem desfecho sem nenhuma mensagem parada — um {@code ack} sem
 * transicao, uma mensagem descartada —, e so o Postgres sabe disso.
 *
 * <p>Nao e um gauge de Videos por estado, e nao deve crescer para isso: os dois valores do
 * atributo {@code estado} contam so o que passou do limiar, e a regra de cada um esta no
 * {@code ContarVideosPresosUseCase}.
 *
 * <h2>Forma</h2>
 *
 * Gauge assincrono, porque a contagem e reativa e o callback do OpenTelemetry e sincrono: quem
 * consulta o banco e o {@code VideosPresosScheduler}, e o callback so le a ultima amostra. Sem
 * amostra — antes da primeira, ou depois de uma que falhou — nao ha serie: um zero diria "nenhum
 * preso" com a autoridade de uma medicao, quando o que o {@code videos} tem e "nao sei".
 *
 * <p>Com duas replicas do {@code videos}, as duas contam o mesmo banco e exportam o mesmo numero;
 * o alerta agrega por {@code max}, e nao por {@code sum}.
 */
@ApplicationScoped
public class MetricaDeVideosPresos {

    private static final AttributeKey<String> ESTADO = AttributeKey.stringKey("estado");
    private static final Attributes PROCESSANDO = Attributes.of(ESTADO, EstadoVideo.PROCESSANDO.name());
    private static final Attributes RECEBIDO = Attributes.of(ESTADO, EstadoVideo.RECEBIDO.name());

    private final AtomicReference<VideosPresos> ultimaAmostra = new AtomicReference<>();

    public MetricaDeVideosPresos(Meter meter) {
        meter.gaugeBuilder("fiapx.videos.presos")
                .ofLongs()
                .setUnit("{video}")
                .setDescription("Vídeos presos além do limiar, por estado (ticket 106)")
                .buildWithCallback(medicao -> {
                    var presos = ultimaAmostra.get();
                    if (presos != null) {
                        medicao.record(presos.processando(), PROCESSANDO);
                        medicao.record(presos.recebidos(), RECEBIDO);
                    }
                });
    }

    public void registrarAmostra(VideosPresos presos) {
        ultimaAmostra.set(presos);
    }

    public void descartarAmostra() {
        ultimaAmostra.set(null);
    }
}
