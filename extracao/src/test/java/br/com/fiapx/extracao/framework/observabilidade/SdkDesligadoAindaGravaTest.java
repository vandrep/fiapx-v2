package br.com.fiapx.extracao.framework.observabilidade;

import io.opentelemetry.api.trace.Tracer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trava o achado do ticket 061: {@code quarkus.otel.sdk.disabled=true} <b>nao</b> impede o SDK
 * de gravar. A suite roda com essa chave ligada ({@code %test} no
 * {@code application.properties}), entao este teste mede exatamente a configuracao sobre a qual
 * o repositorio afirmava o contrario.
 *
 * <p>Ele nao afirma que gravar e o comportamento desejado — afirma que e o comportamento
 * <b>atual</b>, e que ele nao pode mudar em silencio. Enquanto isto passar:
 *
 * <ul>
 *   <li>o guarda por {@code isRecording()} do {@link Rastro} nunca dispara em lugar nenhum
 *       deste repositorio, e o "caminho cru" documentado la e teorico;</li>
 *   <li>o overlay de carga mede um sistema instrumentado sem coletor, e nao "codigo que nao
 *       instrumenta nada" — e a decisao do ticket 062 e que ele fica assim e passa a dizer
 *       isso, porque nenhuma chave que pararia o span alcanca um overlay de Compose.</li>
 * </ul>
 *
 * <p>O 062 tambem estreitou o mecanismo: a chave desliga metrica e log de verdade (sem reader o
 * meter vira no-op, sem processor o logger tambem), e so o trace escapa, porque o
 * {@code SdkTracerProvider} nao tem esse atalho e o sampler nasce amostrando. E o sampler que
 * este teste mede por tabela.
 *
 * <p>Se um upgrade do Quarkus fizer a chave desligar o SDK de verdade, este teste reprova. Isso
 * e o ponto: e o sinal para reabrir o 062 — cuja decisao se apoia neste comportamento — e
 * reescrever os comentarios que o 061 corrigiu, em vez de a mudanca passar despercebida e a
 * proxima investigacao repetir o beco sem saida.
 */
@QuarkusTest
class SdkDesligadoAindaGravaTest {

    @Inject
    Tracer tracer;

    @ConfigProperty(name = "quarkus.otel.sdk.disabled")
    boolean sdkDesligado;

    @Test
    void aChaveQueDizDesligarOSdkNaoImpedeOSpanDeGravar() {
        assertTrue(sdkDesligado, "a suite tem de rodar com quarkus.otel.sdk.disabled=true");

        var span = tracer.spanBuilder("extracao.sonda-de-gravacao").startSpan();
        try {
            assertTrue(span.isRecording(),
                    "medido no ticket 061: com o SDK 'desligado' o span continua gravando."
                            + " Se isto reprovou, a chave passou a desligar de verdade — reabra o"
                            + " ticket 062 e reescreva o javadoc do Rastro antes de mudar este teste");
        } finally {
            span.end();
        }
    }
}
