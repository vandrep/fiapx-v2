package br.com.fiapx.extracao.framework.observabilidade;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.List;

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
 * <h2>Os limites de bucket, e por que eles nao podem ser os default</h2>
 *
 * Um histograma sem {@code explicit_bucket_boundaries} herda os default do OpenTelemetry —
 * {@code 0, 5, 10, 25, ... 10000} —, que sao pensados para <b>milissegundos</b>. Contra
 * segundos eles nao medem nada: toda Extracao deste sistema cabe no primeiro bucket, e ali o
 * {@code histogram_quantile} e interpolacao linear dentro de {@code [0, 5]}. O ticket 092
 * mediu a consequencia — a expressao de manual devolveu {@code NaN} em <b>todas</b> as
 * amostras de uma janela de uma hora, e o painel teve de contornar com {@code _sum / _count}.
 * A media continua no painel, exata; o que os limites deste campo devolvem e a cauda, que
 * media nenhuma responde e que e a pergunta para a qual um histograma existe (ticket 094).
 *
 * <p>A escala tem tres faixas, e cada limite que as fecha e um numero que existe fora daqui:
 *
 * <ul>
 *   <li><b>{@code 0,1} a {@code 1}</b> — onde vive a Extracao curta. O fixture de controle leva
 *       0,19 s e a falha no ffprobe 0,048 s (medidos no 092): a falha rapida fica inteira no
 *       primeiro bucket <b>de proposito</b>, porque um limite em 0,05 cortaria aquela populacao
 *       no meio da moda dela, e a cauda que interessa do lado do {@code falhou} e a de cima.</li>
 *   <li><b>{@code 2,5} a {@code 120}</b> — o regime sob fila, onde a duracao passa a incluir
 *       espera por CPU. A corrida de 20 min do ticket 093 mediu media de 1,00 s nas concluidas
 *       misturando fixtures, contra 0,19 s so com o de controle; a corrida que validou estes
 *       limites (094) poe 20 de 193 concluidas entre 5 s e 10 s.</li>
 *   <li><b>{@code 300} e {@code 420}</b> — os tetos. {@code timeout-ffmpeg-segundos} e 300, e o
 *       teto de relogio de uma Extracao inteira e 330 s de processo externo mais o I/O que nao
 *       tem teto proprio ({@code application.properties}). O limite em 420 — o mesmo
 *       {@code dreno-timeout-segundos} — e o que da bucket proprio a quem morre no teto: sem
 *       ele o {@code +Inf} soma "bateu no teto" com "passou de todo teto conhecido", que sao
 *       diagnosticos diferentes. A separacao mora na <b>serie</b>, e nao no quantil: o
 *       {@code histogram_quantile} interpola dentro de {@code (300, 420]} e devolve 420 cravado
 *       quando o quantil cai no {@code +Inf}, entao quem responde "bateu ou passou?" e a
 *       contagem dos dois buckets, nao a curva.</li>
 * </ul>
 *
 * <p>Medido contra a stack com estes limites, numa corrida de 20 min do
 * {@code scripts/trafego.sh} — 193 concluidas e 10 falhas: p50 0,42 s, p90 5,2 s, p95 7,6 s,
 * p99 9,5 s, com as concluidas ocupando <b>seis</b> buckets. Onde o 092 lia {@code NaN} em
 * todas as amostras de uma janela de uma hora, le-se agora numero. Duas leituras que a mesma
 * corrida cobra honestidade:
 *
 * <ul>
 *   <li>as <b>10 falhas cairam todas no primeiro bucket</b>, porque toda falha daquela corrida
 *       e recusa do ffprobe (~0,05 s). Quantil de {@code falhou} ali e teto — "menos de 0,1 s" —,
 *       e nao medida; a resolucao do lado do {@code falhou} esta em cima, no teto do ffmpeg;</li>
 *   <li>os cinco limites acima de {@code 10} ficaram <b>vazios</b>, e e o esperado: eles cobrem o
 *       regime de teto, que trafego sintetico saudavel nao produz. Bucket vazio custa uma serie
 *       e paga no dia em que a Extracao encosta no teto — que e justamente o dia em que ninguem
 *       tem tempo de reconfigurar histograma.</li>
 * </ul>
 *
 * <p>O criterio dos limites e a <b>cauda</b>, e nao a moda, porque e so a cauda que falta
 * responder: duracao tipica ja sai exata do {@code _sum / _count}, que nao depende de bucket
 * nenhum e continua no painel. E a cauda esta resolvida — p90, p95 e p99 cairam em buckets
 * distintos na corrida de validacao. A moda ficou grossa de proposito: 114 das 193 concluidas
 * cairam entre 0,25 s e 0,5 s, entao a mediana e interpolacao dentro daquele bucket, e um limite
 * escolhido para parti-lo estaria ajustado a este host e a esta mistura de fixtures. A moda anda
 * com o hardware; a media, que e quem responde por ela, nao erra quando ela andar.
 *
 * <p>Por {@code setExplicitBucketBoundariesAdvice}, e nao por uma <i>view</i> no
 * {@code application.properties}: a advice viaja com o instrumento, entao quem le esta classe
 * le os limites junto do que eles medem — e uma view teria de nomear a metrica de novo, num
 * arquivo onde nada mais fala dela.
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

    /** O porque de cada faixa esta no javadoc da classe. */
    private static final List<Double> LIMITES_EM_SEGUNDOS =
            List.of(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 420.0);

    private static final AttributeKey<String> RESULTADO = AttributeKey.stringKey("resultado");
    private static final Attributes CONCLUIDA = Attributes.of(RESULTADO, "concluida");
    private static final Attributes FALHOU = Attributes.of(RESULTADO, "falhou");

    private final DoubleHistogram duracao;

    public DuracaoDaExtracao(Meter meter) {
        this.duracao = meter.histogramBuilder("fiapx.extracao.duracao")
                .setUnit("s")
                .setDescription("Duração da Extração de um Vídeo, do ffprobe ao Pacote empacotado")
                .setExplicitBucketBoundariesAdvice(LIMITES_EM_SEGUNDOS)
                .build();
    }

    /** Registra a tentativa. {@code concluiu} distingue as duas populacoes, nao o motivo. */
    public void registrar(Duration decorrido, boolean concluiu) {
        duracao.record(decorrido.toNanos() / 1_000_000_000.0, concluiu ? CONCLUIDA : FALHOU);
    }
}
