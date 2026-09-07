package br.com.fiapx.extracao.framework.observabilidade;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Trava o achado do ticket 063: o par {@code makeCurrent()}/{@code close()} do {@link Rastro}
 * nao pode depender de onde a cadeia assincrona termina.
 *
 * <h2>O que foi medido, e onde</h2>
 *
 * A sonda do ticket 063 instrumentou os dois metodos do {@link Rastro} nos <b>tres</b> servicos
 * e rodou a suite inteira. Dos dez pontos de instrumentacao, oito abrem o escopo sobre um
 * contexto duplicado do Vert.x — e ali atravessar thread e o desenho, porque o
 * {@code QuarkusContextStorage} prende o escopo ao contexto, nao a thread. Dois nao:
 *
 * <ul>
 *   <li>{@code extracao.frames}: {@code Vertx.currentContext()} nulo nas 4 de 4 Extracoes, e o
 *       escopo fechou numa thread diferente da que o abriu nas 4;</li>
 *   <li>{@code extracao.gravar-pacote}: contexto nulo nas 3 de 3, com o fechamento caindo na
 *       mesma thread por acaso — o {@code putObject} do S3 completou onde comecou.</li>
 * </ul>
 *
 * Sem contexto duplicado o armazenamento e o {@code MDCEnabledContextStorage}, sobre uma
 * {@code ThreadLocal}: o {@code close} numa thread B e ignorado pelo
 * {@code ThreadLocalContextStorage} (ele so restaura quando o contexto corrente e o que ele
 * anexou), entao a thread A <b>fica com o span encerrado como contexto corrente para sempre</b>
 * — e uma delas, medida, e a {@code InnocuousThread-1} do pool comum da JVM. O MDC do
 * {@code MDCEnabledContextStorage}, esse, e escrito na thread B sem nenhuma guarda.
 *
 * <p>Este teste reproduz exatamente essa forma — abre numa thread, completa noutra, sem
 * contexto Vert.x nenhum —, que e a razao de ele nao ser {@code @QuarkusTest}: o
 * armazenamento por {@code ThreadLocal} e o caso a testar, e um {@code ExecutorService} de uma
 * thread o produz sem infraestrutura.
 */
class EscopoNaoAtravessaThreadTest {

    private final Tracer tracer = SdkTracerProvider.builder().build().get("teste-063");

    @Test
    void emTornoNaoDeixaOEscopoAbertoNaThreadQueOAbriu() throws Exception {
        var rastro = new Rastro(tracer);
        var abriu = thread("sonda-063-abre");
        var completa = thread("sonda-063-completa");
        try {
            var pendente = new CompletableFuture<String>();

            var futuro = abriu.submit(() -> rastro.emTorno("extracao.sonda", () -> pendente)).get();
            completa.submit(() -> pendente.complete("pronto")).get();
            futuro.get(5, TimeUnit.SECONDS);

            assertFalse(correnteEmValida(abriu),
                    "a thread que abriu o escopo ficou com o span encerrado como contexto corrente:"
                            + " abrir numa thread e fechar noutra vaza o contexto na primeira");
            assertFalse(correnteEmValida(completa),
                    "a thread que completou a cadeia herdou contexto que nao e dela");
        } finally {
            abriu.shutdownNow();
            completa.shutdownNow();
        }
    }

    @Test
    void naMensagemSemContextoDuplicadoNaoPenduraEscopoNemMdcNaThread() throws Exception {
        var rastro = new Rastro(tracer);
        var abriu = thread("sonda-063-abre");
        var completa = thread("sonda-063-completa");
        try {
            var pendente = new CompletableFuture<String>();

            var uni = rastro.naMensagem("extracao.sonda-mensagem", UUID.randomUUID(),
                    Message.of("carga"), () -> Uni.createFrom().completionStage(pendente));
            var assinado = abriu.submit(() -> uni.subscribeAsCompletionStage()).get();
            completa.submit(() -> pendente.complete("pronto")).get();
            assinado.get(5, TimeUnit.SECONDS);

            assertFalse(correnteEmValida(abriu),
                    "sem contexto duplicado do Vert.x o escopo do consumo nao pode ficar preso"
                            + " na thread que assinou a cadeia");
            assertNull(abriu.submit(() -> MDC.get(Rastro.ID_VIDEO)).get(),
                    "o idVideo ficou no MDC da thread que assinou: todo log seguinte dela sairia"
                            + " pendurado num Video que ja terminou");
        } finally {
            abriu.shutdownNow();
            completa.shutdownNow();
        }
    }

    private static boolean correnteEmValida(ExecutorService thread) throws Exception {
        return thread.submit(() -> Span.current().getSpanContext().isValid()).get();
    }

    private static ExecutorService thread(String nome) {
        return Executors.newSingleThreadExecutor(tarefa -> new Thread(tarefa, nome));
    }
}
