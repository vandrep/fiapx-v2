package br.com.fiapx.extracao.framework.service;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.faulttolerance.api.AsynchronousNonBlocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Trava o comportamento de que {@link ArquivoMinioClient} depende (ADR 0001), para um
 * upgrade futuro do SmallRye Fault Tolerance nao quebrar o retry em silencio. Dois achados
 * reais desta implementacao, territorio novo neste repo:
 *
 * <p>1. {@code @Retry} so intercepta quando o metodo declara {@code CompletionStage<T>}, nao
 * {@code CompletableFuture<T>} — {@code CompletionStageSupport.applies} compara
 * {@code CompletionStage.class.equals(returnType)}, exato, sem considerar subtipos.
 *
 * <p>2. O metodo anotado nao pode ser chamado de dentro do mesmo bean (self-invocation
 * ignora o proxy do CDI, e o interceptor nunca dispara) — precisa estar num bean separado,
 * chamado de fora. E o motivo de {@link ArquivoMinioClient} ser um bean a parte do adapter
 * que o chama.
 */
@QuarkusTest
class RetryComCompletionStageTest {

    @Inject
    BeanQueDelegaParaORetry bean;

    @Inject
    BeanComRetry beanComRetry;

    @Test
    void retryReexecutaQuandoOCompletionStageFalhaAssincronamenteEChamadoDeOutroBean() throws Exception {
        beanComRetry.resetar();

        var resultado = bean.chamar().get();

        assertEquals("ok", resultado);
        assertEquals(3, beanComRetry.tentativas());
    }

    @ApplicationScoped
    public static class BeanQueDelegaParaORetry {
        @Inject
        BeanComRetry beanComRetry;

        public CompletableFuture<String> chamar() {
            return beanComRetry.falhaDuasVezesDepoisSucede().toCompletableFuture();
        }
    }

    @ApplicationScoped
    public static class BeanComRetry {
        private final AtomicInteger contador = new AtomicInteger();

        public void resetar() {
            contador.set(0);
        }

        public int tentativas() {
            return contador.get();
        }

        @Retry(maxRetries = 3, delay = 0)
        @AsynchronousNonBlocking
        public CompletionStage<String> falhaDuasVezesDepoisSucede() {
            var tentativa = contador.incrementAndGet();
            if (tentativa < 3) {
                return CompletableFuture.failedFuture(new RuntimeException("falha simulada " + tentativa));
            }
            return CompletableFuture.completedFuture("ok");
        }
    }
}
