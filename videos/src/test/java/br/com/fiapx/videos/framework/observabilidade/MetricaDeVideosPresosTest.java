package br.com.fiapx.videos.framework.observabilidade;

import br.com.fiapx.videos.core.usecases.video.ContarVideosPresosUseCase.VideosPresos;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que o alerta do ticket 106 le no Prometheus: uma serie por estado, com a contagem da ultima
 * amostra. Fora do CDI pelo mesmo motivo do {@code LimitesDeBucketDaDuracaoTest} do
 * {@code extracao}: a suite roda com o SDK desligado, e ali o meter injetado e no-op.
 */
class MetricaDeVideosPresosTest {

    private static final AttributeKey<String> ESTADO = AttributeKey.stringKey("estado");

    /** Copia do reader do {@code extracao}: o repositorio nao traz {@code opentelemetry-sdk-testing}. */
    private static final class Coletor implements MetricReader {

        private CollectionRegistration registro = CollectionRegistration.noop();

        @Override
        public void register(CollectionRegistration registro) {
            this.registro = registro;
        }

        Collection<MetricData> coleta() {
            return registro.collectAllMetrics();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType tipo) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode forceFlush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private Map<String, Long> coletarPorEstado(Consumer<MetricaDeVideosPresos> amostras) {
        var coletor = new Coletor();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(coletor).build()) {
            amostras.accept(new MetricaDeVideosPresos(provider.get("teste")));
            return coletor.coleta().stream()
                    .filter(m -> m.getName().equals("fiapx.videos.presos"))
                    .flatMap(m -> m.getLongGaugeData().getPoints().stream())
                    .collect(Collectors.toMap(p -> p.getAttributes().get(ESTADO), LongPointData::getValue));
        }
    }

    @Test
    void cadaEstadoViraUmaSerieComAContagemDaUltimaAmostra() {
        var series = coletarPorEstado(metrica -> {
            metrica.registrarAmostra(new VideosPresos(5, 7));
            metrica.registrarAmostra(new VideosPresos(2, 0));
        });

        assertEquals(Map.of("PROCESSANDO", 2L, "RECEBIDO", 0L), series);
    }

    @Test
    void semAmostraNaoHaSerieEmVezDeUmZeroQueMente() {
        // Antes da primeira contagem, ou depois de uma que falhou, o `videos` nao sabe. Um zero
        // ali diria "nenhum preso" com a autoridade de uma medicao.
        assertTrue(coletarPorEstado(metrica -> { }).isEmpty());
        assertTrue(coletarPorEstado(metrica -> {
            metrica.registrarAmostra(new VideosPresos(3, 1));
            metrica.descartarAmostra();
        }).isEmpty());
    }
}
