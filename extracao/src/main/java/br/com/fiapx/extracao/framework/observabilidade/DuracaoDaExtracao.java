package br.com.fiapx.extracao.framework.observabilidade;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

/**
 * A <b>unica</b> metrica propria de dominio do sistema (ticket 059): quanto tempo a Extracao
 * de um Video leva.
 *
 * <h2>Por que esta, e por que so esta</h2>
 *
 * A Extracao e 98,2% do tempo de servico — numero medido no ticket 027 — e roda dentro de um
 * processo externo, o {@code ffmpeg}. Nenhuma auto-instrumentacao enxerga la dentro: para o
 * OpenTelemetry, o {@code ProcessBuilder} e um buraco. Toda a outra parte do caminho (HTTP,
 * publicacao, consumo, S3, Postgres) ja e coberta pelo que a extensao emite sozinha, e
 * duplicar isso com metrica propria so criaria uma segunda verdade para conferir.
 *
 * <p>O que <b>nao</b> entrou, e de proposito: Videos por estado. O endpoint de listagem ja
 * responde essa pergunta com a autoridade do banco, e um gauge exigiria varredura periodica no
 * Postgres so para alimentar um numero que ninguem consulta em regime.
 *
 * <h2>Nome e unidade</h2>
 *
 * {@code fiapx.extracao.duracao} usa o vocabulario canonico do {@code CONTEXT.md} — Extracao e
 * o termo do dominio, e traduzi-lo para {@code processing_time} inventaria um sinonimo que nao
 * existe em lugar nenhum do repositorio. A regra vale so para o que e <b>nosso</b>: o que a
 * auto-instrumentacao emite fica como o OpenTelemetry emite, porque ali o nome e contrato com
 * a ferramenta.
 *
 * <p>Segundos, e nao milissegundos: e a unidade canonica do OpenTelemetry para duracao, e o
 * Prometheus a espera no sufixo (`fiapx_extracao_duracao_seconds`).
 *
 * <h2>O atributo `resultado`</h2>
 *
 * Continua sendo <b>uma</b> metrica; o atributo so a separa em duas distribuicoes. Sem ele, uma
 * Extracao que morre no teto de 300 s do {@code ffmpeg} entraria na mesma distribuicao das que
 * terminaram, e a mediana passaria a medir uma mistura de duas populacoes diferentes — que e o
 * defeito classico de medir duracao sem separar desfecho.
 */
@ApplicationScoped
public class DuracaoDaExtracao {

    private static final AttributeKey<String> RESULTADO = AttributeKey.stringKey("resultado");
    private static final Attributes CONCLUIDA = Attributes.of(RESULTADO, "concluida");
    private static final Attributes FALHOU = Attributes.of(RESULTADO, "falhou");

    private final DoubleHistogram duracao;

    public DuracaoDaExtracao(Meter meter) {
        this.duracao = meter.histogramBuilder("fiapx.extracao.duracao")
                .setUnit("s")
                .setDescription("Duração da Extração de um Vídeo, do ffprobe ao Pacote empacotado")
                .build();
    }

    /** Registra a tentativa. {@code concluiu} distingue as duas populacoes, nao o motivo. */
    public void registrar(Duration decorrido, boolean concluiu) {
        duracao.record(decorrido.toNanos() / 1_000_000_000.0, concluiu ? CONCLUIDA : FALHOU);
    }
}
