package br.com.fiapx.videos.framework.observabilidade;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.TracingMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Message;
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
 * <b>corrente durante todo o trabalho assincrono</b>. O {@code QuarkusContextStorage} guarda o
 * contexto no contexto duplicado do Vert.x, nao numa ThreadLocal, entao ele sobrevive aos
 * saltos de thread da cadeia (worker pool do {@code @Blocking}, thread do SDK da AWS,
 * scheduler do fault tolerance) — o mesmo mecanismo pelo qual o Panache acha a sessao.
 *
 * <h2>idVideo</h2>
 *
 * {@code idVideo} entra ao mesmo tempo como atributo do span e como campo do MDC. Sao os dois
 * lados do mesmo requisito: o atributo e o que a busca no Tempo casa, e o MDC e o que o
 * exportador de log do OpenTelemetry copia para os atributos do registro, pendurando o log no
 * span certo. E o {@code idVideo} do contrato de mensagens, o mesmo que um humano digita — o
 * {@code trace_id} identifica a travessia, que e outra coisa.
 *
 * <h2>Com o SDK desligado</h2>
 *
 * Em teste e em dev o SDK esta desligado e o span nasce sem gravar. O guarda por
 * {@link Span#isRecording()} devolve o trabalho cru nesse caso: nada de escopo aberto, nada de
 * MDC. Sem ele, fechar um escopo no-op a partir da thread que completou a cadeia so produziria
 * ruido de log numa suite que nao exporta nada.
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
            var escopo = span.makeCurrent();
            MDC.put(ID_VIDEO, idVideo.toString());
            try {
                return trabalho.get().onItemOrFailure().invoke((ignorado, falha) -> encerrar(span, escopo, falha));
            } catch (RuntimeException erroSincrono) {
                // O supplier pode estourar antes de existir cadeia onde pendurar o invoke.
                encerrar(span, escopo, erroSincrono);
                throw erroSincrono;
            }
        });
    }

    /**
     * Envolve uma ida a um recurso externo num adapter de I/O — MinIO, SMTP. Filho do que
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
        var escopo = span.makeCurrent();
        try {
            return trabalho.get().whenComplete((ignorado, falha) -> encerrar(span, escopo, falha));
        } catch (RuntimeException erroSincrono) {
            encerrar(span, escopo, erroSincrono);
            throw erroSincrono;
        }
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

    private static void encerrar(Span span, Scope escopo, Throwable falha) {
        if (falha != null) {
            span.recordException(falha);
            span.setStatus(StatusCode.ERROR, String.valueOf(falha.getMessage()));
        }
        MDC.remove(ID_VIDEO);
        escopo.close();
        span.end();
    }
}
