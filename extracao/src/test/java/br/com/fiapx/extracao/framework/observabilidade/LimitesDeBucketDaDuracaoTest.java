package br.com.fiapx.extracao.framework.observabilidade;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trava o achado do ticket 094: sem limites proprios, {@code fiapx.extracao.duracao} herda os
 * <i>default</i> do OpenTelemetry — {@code 0, 5, 10, 25, ... 10000} —, que sao pensados para
 * <b>milissegundos</b>, e a metrica e gravada em <b>segundos</b>. Toda observacao caia no
 * primeiro bucket, e o {@code histogram_quantile} do painel devolvia {@code NaN} em todas as
 * amostras de uma janela de uma hora (medido no ticket 092).
 *
 * <p>Este teste nao passa pelo CDI de proposito: a suite roda com
 * {@code quarkus.otel.sdk.disabled=true} ({@link SdkDesligadoAindaGravaTest} mede o que essa
 * chave faz e o que nao faz), e sem reader o meter injetado e no-op — um histograma no-op nao
 * tem bucket para conferir. O {@link SdkMeterProvider} montado aqui e o menor lugar onde a
 * <i>advice</i> do instrumento vira um ponto exportado, que e o que o Prometheus enxerga.
 */
class LimitesDeBucketDaDuracaoTest {

    /**
     * Um reader que so coleta quando lhe pedem. O repositorio nao tem
     * {@code opentelemetry-sdk-testing} no classpath, e esta e a peca inteira que ele traria.
     */
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

    /** Registra o que o {@code registro} mandar e devolve os pontos exportados da metrica. */
    private List<HistogramPointData> coletarPontos(Consumer<DuracaoDaExtracao> registro) {
        var coletor = new Coletor();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(coletor).build()) {
            registro.accept(new DuracaoDaExtracao(provider.get("teste")));
            return coletor.coleta().stream()
                    .filter(m -> m.getName().equals("fiapx.extracao.duracao"))
                    .flatMap(m -> m.getHistogramData().getPoints().stream())
                    .toList();
        }
    }

    /** O caso comum: so Extracoes concluidas, entao uma distribuicao so. */
    private HistogramPointData concluidas(Duration... duracoes) {
        var pontos = coletarPontos(metrica -> {
            for (var duracao : duracoes) {
                metrica.registrar(duracao, true);
            }
        });
        assertEquals(1, pontos.size(), "esperava um ponto de histograma para resultado=concluida");
        return pontos.getFirst();
    }

    @Test
    void osLimitesSaoOsDaEscalaDeSegundos() {
        var ponto = concluidas(Duration.ofMillis(190));

        assertEquals(
                List.of(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 420.0),
                ponto.getBoundaries(),
                "sem limites proprios o histograma herda os default do OTel, que sao de"
                        + " milissegundos — ver ticket 094");
    }

    @Test
    void duracoesQueOsDefaultConfundiamCaemEmBucketsDiferentes() {
        // Os tres numeros sao medidos: 0,048 s e a falha no ffprobe (ticket 092), 0,19 s e a
        // Extracao do fixture de controle (092) e 300 s e o timeout do ffmpeg
        // (fiapx.extracao.timeout-ffmpeg-segundos). Nos default do OTel os tres caem no mesmo
        // primeiro bucket, e e por isso que o quantil nao media nada.
        var ponto = concluidas(
                Duration.ofMillis(48), Duration.ofMillis(190), Duration.ofSeconds(300));

        var ocupados = ponto.getCounts().stream().filter(c -> c > 0).count();
        assertEquals(3, ocupados,
                "as tres duracoes medidas no 092 precisam cair em tres buckets distintos");
    }

    @Test
    void oTetoDoFfmpegCabeDentroDoUltimoBucketFinito() {
        // O teto de relogio de uma Extracao e 330 s de processo externo (30 s de ffprobe mais
        // 300 s de ffmpeg) mais download, empacotamento e upload, que nao tem teto proprio.
        // Uma Extracao que morre nesse teto tem de ser distinguivel de uma que passou dele: no
        // +Inf as duas se somam, e o quantil volta a ser o maior limite finito para as duas.
        var ponto = concluidas(Duration.ofSeconds(330));

        var ultimoFinito = ponto.getCounts().get(ponto.getCounts().size() - 2);
        var acimaDeTudo = ponto.getCounts().getLast();
        assertEquals(1, ultimoFinito, "a Extracao no teto do ffmpeg tem de ter bucket proprio");
        assertEquals(0, acimaDeTudo, "e nao pode cair no +Inf junto com o que passou de todo teto");
    }

    @Test
    void oAtributoResultadoContinuaSeparandoAsDuasPopulacoes() {
        // A advice muda o bucket, nao a metrica: o corte por `resultado` do ticket 059 e o que
        // impede a mediana de medir uma mistura de duas populacoes, e ele continua de pe.
        var pontos = coletarPontos(metrica -> {
            metrica.registrar(Duration.ofMillis(190), true);
            metrica.registrar(Duration.ofMillis(48), false);
        });

        assertEquals(2, pontos.size(), "esperava uma distribuicao por resultado");
        var resultados = pontos.stream()
                .map(p -> p.getAttributes().asMap().values().iterator().next())
                .map(Object::toString)
                .sorted()
                .toList();
        assertEquals(List.of("concluida", "falhou"), resultados);
        assertTrue(pontos.stream().allMatch(p -> p.getBoundaries().size() == 12),
                "as duas distribuicoes usam os mesmos limites");
        assertNotEquals(pontos.get(0).getSum(), pontos.get(1).getSum());
    }
}
