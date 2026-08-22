package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Monta command e chama o controller, sem regra propria (docs/contratos/mensagens.md §
 * Camadas). {@code failure-strategy=requeue} no canal: um {@link
 * br.com.fiapx.extracao.core.usecases.extracao.ProcessarExtracaoUseCase} que completa
 * normalmente (falha permanente ja publicada) vira <b>ack</b>; um que completa
 * excepcionalmente (falha transitoria) vira <b>nack</b> com requeue, e o {@code
 * x-delivery-limit=3} da fila quorum decide quando esgotar (ADR 0001).
 *
 * <p>{@code @Blocking} despacha esta chamada para o worker pool padrao do Quarkus, porque o
 * pipeline roda ffmpeg como processo externo — bloqueante e pesado (docs/pesquisa/
 * ffmpeg-extracao.md). A pesquisa 006 recomendou um pool nomeado
 * ({@code @Blocking("extracao-pool")}); achado real desta implementacao: essa sobrecarga de
 * {@code @Blocking} nao existe em {@code io.smallrye.common.annotation.Blocking} — a unica
 * anotacao {@code @Blocking} presente no classpath desta versao do SmallRye Reactive
 * Messaging (4.32.1) e um marcador sem parametros. Um pool nomeado nao e necessario para a
 * corretude aqui: {@code max-outstanding-messages=1} ja limita este canal a uma mensagem em
 * voo por vez. O adapter de extracao, por sua vez, ainda garante seu proprio deslocamento
 * para fora da event loop antes de invocar o processo: o download do MinIO que precede a
 * extracao completa no thread do SDK da AWS, nao neste worker pool.
 */
@ApplicationScoped
public class ExtrairVideoConsumer {

    @Inject
    ExtracaoController extracaoController;

    @Incoming("extrair-video")
    @Blocking
    public Uni<Void> consumir(ExtrairVideo comando) {
        return Uni.createFrom().completionStage(extracaoController.processarExtrairVideo(
                comando.idVideo(), comando.chaveVideo(), comando.chaveDestinoPacote()));
    }
}
