package br.com.fiapx.videos.framework.observabilidade;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.TracingMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A costura do rastro deste servico (ticket 059). Existe porque a auto-instrumentacao para
 * exatamente onde o trabalho comeca, e sozinha ela nao responde a pergunta do incidente de
 * 06/09 — <i>onde este Video parou</i>.
 *
 * <h2>Por que um span nosso no consumo</h2>
 *
 * O conector RabbitMQ ja abre um span de recebimento a partir do {@code traceparent} que veio
 * no header AMQP, mas ele o <b>encerra na hora</b>: o
 * {@code TracingUtils.traceIncoming} do SmallRye chama {@code instrumenter.end} e fecha o
 * escopo antes de o metodo {@code @Incoming} rodar, deixando so uma migalha no
 * {@link TracingMetadata} da mensagem. Duas consequencias, e as duas quebram o requisito:
 *
 * <ol>
 *   <li>o span de recebimento tem duracao ~0, entao ele nao diz quanto o trabalho levou nem
 *       onde ele falhou;</li>
 *   <li>sem contexto corrente, a <b>publicacao seguinte</b> vira uma raiz nova — o
 *       {@code traceOutgoing} usa {@code Context.current()} como pai. O rastro se partiria em
 *       cada salto entre servicos, que e precisamente o que este ticket precisa que nao
 *       aconteca.</li>
 * </ol>
 *
 * Por isso {@link #naMensagem} pendura um span proprio no contexto da mensagem e o mantem
 * <b>corrente durante todo o trabalho assincrono</b>. Quem o carrega e o contexto duplicado do
 * Vert.x, onde o {@code QuarkusContextStorage} guarda o contexto do OpenTelemetry — o mesmo
 * mecanismo pelo qual o Panache acha a sessao. Ele nao esta sempre la, e a secao seguinte e
 * sobre isso.
 *
 * <p><b>Neste servico a cadeia do consumo nao troca de thread</b> (ticket 090), e isso foi
 * medido, nao suposto. Do {@code @Incoming} ao ack, o trabalho roda inteiro na event loop que
 * entregou a mensagem: os tres consumidores do {@code ExtracaoEventosConsumer} devolvem
 * {@code Uni} com ack manual e <b>nenhum</b> deles anota {@code @Blocking}, o Postgres reativo
 * devolve a continuacao ao contexto de quem chamou, e o publish do {@code VideoFalhou} tambem.
 * O SDK da AWS nao aparece aqui: as duas idas ao MinIO do {@code ArquivoGateway} —
 * {@code gravarVideo} e {@code abrirPacote} — sao chamadas so pelo {@code EnviarVideoUseCase} e
 * pelo {@code BaixarPacoteUseCase}, os dois da borda HTTP. O terceiro chamador do gateway,
 * {@code PublicarExtrairVideo}, pede so a {@code chaveDoPacote}, que e string pura e nao toca o
 * {@code S3AsyncClient}. Nenhum dos tres {@code @Incoming} desemboca em nada disso; e quando o
 * SDK aparece, no caminho da borda, o {@code ArquivoMinioAdapter.noContextoDeChamada} existe
 * justamente para <b>sair</b> da thread dele. A medicao correu o mais longo dos tres consumos, o
 * de {@code extracao.falhou} (SELECT, UPDATE, publish, UPDATE), pelo
 * {@code ExtracaoRapidaPelaBordaTest}: uma event loop so, da entrada ao ack.
 *
 * <p>Sem salto de thread, por que o contexto duplicado ainda e o que carrega o span? Porque a
 * cadeia <b>se interrompe</b> mesmo sem mudar de thread: a repeticao do
 * {@code RepeticaoNoPostgres} espera 2 s antes de reassinar a operacao, e no intervalo nao ha
 * quadro de pilha nenhum onde o contexto pudesse estar preso. Medido junto: a continuacao volta
 * no mesmo contexto duplicado, com {@code isOnDuplicatedContext()} verdadeiro, e o span segue
 * corrente do outro lado da espera. E o contexto que guarda isso — a thread e so onde ele calhou
 * de rodar.
 *
 * <h2>Onde o escopo pode atravessar thread, e onde nao (ticket 063)</h2>
 *
 * Um {@link Scope} aberto numa thread e fechado noutra so e seguro quando o armazenamento e o
 * contexto duplicado do Vert.x: ali o par abre/fecha e <b>por contexto</b>, nao por thread. Sem
 * contexto duplicado o armazenamento cai numa {@code ThreadLocal}, o {@code close} vindo de
 * outra thread e ignorado em silencio, e a thread que abriu fica com o span, <b>ja encerrado</b>,
 * como contexto corrente. O {@code extracao} tem dois pontos assim, e eles rodam em toda
 * Extracao — a medicao, a regra e o que ela endireitou na arvore do trace estao no
 * {@code docs/adr/0004-camada-de-observabilidade.md}, secao <i>O escopo so atravessa thread
 * preso ao contexto duplicado do Vert.x</i>, que e o dono dela.
 *
 * <p>Dai as duas formas desta classe nao serem intercambiaveis:
 *
 * <ul>
 *   <li>{@link #naMensagem} <b>precisa</b> do span corrente durante todo o trabalho — e o que faz
 *       a publicacao seguinte ser filha dele —, entao mantem o escopo aberto atravessando thread,
 *       e so quando ha contexto duplicado. Sem ele, degrada de proposito: nem escopo nem MDC, e um
 *       WARN. Perder o encadeamento e ruim, e ainda assim e melhor que pendurar contexto numa
 *       thread que ninguem limpa — e, pela medicao, o ramo nao e alcancado em servico nenhum.</li>
 *   <li>{@link #emTorno} <b>nao precisa</b>. Quem le o contexto corrente e a instrumentacao que
 *       monta a requisicao — aqui, o SDK da AWS em volta do MinIO —, e ela roda no disparo; ja o
 *       {@code ffmpeg} do {@code extracao}, medido junto naquele ticket, nao tem instrumentacao
 *       nenhuma dentro, e la o escopo aberto nao servia a ninguem. O escopo abre e fecha na mesma
 *       thread, em volta do disparo, e o span segue vivo ate a conclusao.</li>
 * </ul>
 *
 * <p>O que a regressao trava esta em {@code EscopoNaoAtravessaThreadTest}, no {@code extracao}
 * — um so, no servico onde o caminho foi medido.
 *
 * <h2>idVideo</h2>
 *
 * {@code idVideo} entra ao mesmo tempo como atributo do span e como campo do MDC. Sao os dois
 * lados do mesmo requisito: o atributo e o que a busca no Tempo casa, e o MDC e o que o
 * exportador de log do OpenTelemetry copia para os atributos do registro, pendurando o log no
 * span certo. E o {@code idVideo} do contrato de mensagens, o mesmo que um humano digita — o
 * {@code trace_id} identifica a travessia, que e outra coisa.
 *
 * <h2>O guarda por {@link Span#isRecording()}, e o que ele NAO cobre</h2>
 *
 * O guarda devolve o trabalho cru quando o span nasce sem gravar: nada de escopo aberto, nada
 * de MDC. Ele existe para o caso de o {@code Tracer} injetado ser no-op — fechar um escopo
 * no-op a partir da thread que completou a cadeia so produziria ruido de log.
 *
 * <p>O que este javadoc afirmava ate o ticket 061, e era falso: que
 * {@code quarkus.otel.sdk.disabled=true} produz esse caso. <b>Nao produz.</b> Medido no
 * Quarkus 3.31.3, com a propriedade ligada por variavel de ambiente e por
 * {@code -D} de sistema, {@code isRecording()} continua {@code true} e o span e um
 * {@code SdkSpan} de verdade: o {@code OpenTelemetryRecorder} pula os customizadores, mas o SDK
 * que ele monta continua gravando. O ticket 062 estreitou o recorte: a chave desliga
 * <b>metrica e log</b> de verdade, e falha so no <b>trace</b>, porque o
 * {@code SdkTracerProvider} e o unico dos tres providers sem o atalho "sem processador, vira
 * no-op". Logo o caminho cru e, na pratica, morto — em teste, em dev e no overlay de carga o
 * codigo roda pelo caminho que abre escopo.
 *
 * <p>Isso importa alem da precisao do comentario: o ticket 061 descartou uma hipotese inteira
 * por acreditar na frase antiga. A decisao que faltava esta tomada — o overlay de carga fica
 * como esta e passa a declarar que mede um sistema instrumentado sem coletor —, e o mecanismo
 * inteiro mora em {@code docs/adr/0004-camada-de-observabilidade.md}, nao aqui.
 *
 * <h2>Onde {@link #emTorno} vale a pena, e onde nao</h2>
 *
 * So no I/O que a auto-instrumentacao nao cobre — e quem decide isso e a medicao, nao o
 * catalogo de extensoes. A borda HTTP e o Postgres aparecem sozinhos: o rastro do envio traz
 * {@code POST /videos}, {@code INSERT video} e {@code SELECT video} sem que ninguem os escreva.
 * O <b>MinIO nao</b>: a extensao da AWS arrasta o {@code opentelemetry-aws-sdk-2.2} e monta o
 * {@code AwsSdkTelemetry}, mas <b>nenhum span de S3 chegou ao Tempo</b> num ciclo completo de
 * Video (ticket 059, verificado no {@code smoke.sh}) — o upload de um Video de 200 MB ficava um
 * vao mudo dentro do span do POST. Daí o span nosso ali, e so ali.
 */
@ApplicationScoped
public class Rastro {

    private static final Logger LOG = Logger.getLogger(Rastro.class);

    /** Nome do atributo de span e da chave de MDC. E o termo do contrato, nao um sinonimo. */
    public static final String ID_VIDEO = "idVideo";

    private final Tracer tracer;

    /**
     * Injecao por construtor, e nao por campo: e o que deixa um teste de unidade montar este
     * bean a mao com um {@code Tracer} no-op, sem container e sem mock. Os consumidores e
     * adapters instrumentados sao testados assim hoje.
     */
    public Rastro(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Envolve o consumo de uma mensagem: span filho do recebimento, {@code idVideo} no span e
     * no MDC, e os dois vivos ate a cadeia terminar.
     */
    public <T> Uni<T> naMensagem(String nome, UUID idVideo, Message<?> mensagem, Supplier<Uni<T>> trabalho) {
        return Uni.createFrom().deferred(() -> {
            var span = tracer.spanBuilder(nome)
                    .setParent(contextoDaMensagem(mensagem))
                    .setAttribute(ID_VIDEO, idVideo.toString())
                    .startSpan();
            if (!span.isRecording()) {
                span.end();
                return trabalho.get();
            }
            if (!VertxContext.isOnDuplicatedContext()) {
                // Ticket 063: sem contexto duplicado, escopo e MDC cairiam numa ThreadLocal que so
                // seria solta quando a cadeia terminasse — noutra thread —, e ninguem a soltaria.
                // Este consumo perde o encadeamento do trace, e nao pendura contexto em thread
                // alheia. Medido: nao acontece em consumo de mensagem em servico nenhum, entao a
                // linha e sinal de mudanca, nao ruido de rotina.
                LOG.warnf("%s fora de contexto duplicado do Vert.x: sem escopo corrente e sem MDC;"
                        + " idVideo=%s", nome, idVideo);
                return comEncerramento(span, () -> { }, trabalho);
            }
            var escopo = span.makeCurrent();
            MDC.put(ID_VIDEO, idVideo.toString());
            return comEncerramento(span, () -> soltar(escopo), trabalho);
        });
    }

    /**
     * O desfecho unico da cadeia do consumo, com o que ela tem a soltar no fim — que pode ser
     * nada, quando nada chegou a ser preso. O {@code catch} existe porque o supplier pode
     * estourar antes de existir cadeia onde pendurar o {@code invoke}.
     */
    private static <T> Uni<T> comEncerramento(Span span, Runnable soltar, Supplier<Uni<T>> trabalho) {
        try {
            return trabalho.get().onItemOrFailure().invoke((ignorado, falha) -> {
                soltar.run();
                encerrar(span, falha);
            });
        } catch (RuntimeException erroSincrono) {
            soltar.run();
            encerrar(span, erroSincrono);
            throw erroSincrono;
        }
    }

    /**
     * Envolve uma ida a um recurso externo num adapter de I/O — aqui, o MinIO. Filho do que
     * estiver corrente, que e o span de {@link #naMensagem} no worker ou o span de servidor
     * HTTP da borda. Sem {@code idVideo}: o adapter de I/O nao o conhece, e o span pai que o
     * carrega ja esta logo acima.
     */
    public <T> CompletableFuture<T> emTorno(String nome, Supplier<CompletableFuture<T>> trabalho) {
        var span = tracer.spanBuilder(nome).startSpan();
        if (!span.isRecording()) {
            span.end();
            return trabalho.get();
        }
        CompletableFuture<T> emVoo;
        // Ticket 063: o escopo abre e fecha na MESMA thread, em volta do disparo do I/O. Quem
        // precisa do span corrente e a instrumentacao que monta a requisicao, e ela roda aqui
        // dentro; o span continua vivo ate a conclusao, so o escopo e que nao viaja com ela.
        try (var escopo = span.makeCurrent()) {
            emVoo = trabalho.get();
        } catch (RuntimeException erroSincrono) {
            encerrar(span, erroSincrono);
            throw erroSincrono;
        }
        return emVoo.whenComplete((ignorado, falha) -> encerrar(span, falha));
    }

    /**
     * Pendura o {@code idVideo} no span que ja esta corrente — o de servidor HTTP que a
     * auto-instrumentacao abriu na borda. Mora aqui, e nao no {@code Resource}, porque o par
     * <i>atributo de span + campo de MDC</i> e a definicao de "este registro fala deste Video",
     * e ela precisa de um dono so: com a mesma dupla escrita em dois lugares, mudar a chave num
     * deles quebraria a busca no outro em silencio.
     *
     * <p>Sem {@code remove}: o MDC do Quarkus vive no contexto duplicado do Vert.x, que morre com
     * a requisicao. Nao ha ThreadLocal a limpar, e limpar cedo apagaria o campo dos logs
     * assincronos emitidos depois do metodo de borda retornar.
     */
    public void marcar(UUID idVideo) {
        Span.current().setAttribute(ID_VIDEO, idVideo.toString());
        MDC.put(ID_VIDEO, idVideo.toString());
    }

    /**
     * O contexto que o conector deixou na mensagem. {@code Context.current()} como alternativa
     * cobre o canal com {@code tracing.enabled=false} e a chamada direta em teste; o span
     * simplesmente vira raiz, em vez de a instrumentacao quebrar.
     */
    private static Context contextoDaMensagem(Message<?> mensagem) {
        return TracingMetadata.fromMessage(mensagem)
                .map(TracingMetadata::getCurrentContext)
                .orElseGet(Context::current);
    }

    private static void encerrar(Span span, Throwable falha) {
        if (falha != null) {
            span.recordException(falha);
            span.setStatus(StatusCode.ERROR, String.valueOf(falha.getMessage()));
        }
        span.end();
    }

    /**
     * Solta o que o consumo prendeu. Chega de qualquer thread, e e seguro justamente por isso:
     * escopo e MDC so foram presos quando havia contexto duplicado do Vert.x, e ali os dois sao
     * do contexto, nao da thread.
     */
    private static void soltar(Scope escopo) {
        MDC.remove(ID_VIDEO);
        escopo.close();
    }
}
