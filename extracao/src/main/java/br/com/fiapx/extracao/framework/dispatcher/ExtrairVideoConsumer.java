package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.framework.shutdown.DrenoDaExtracao;
import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

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
 *
 * <h2>Por que o ack e manual (ticket 035)</h2>
 *
 * O {@link DrenoDaExtracao} segura o {@code SIGTERM} enquanto ha Extracao em voo, e o que ele
 * precisa esperar e o <b>ack</b>, nao o fim do ffmpeg: uma Extracao que termina e perde o
 * canal antes de ackear e reenfileirada do mesmo jeito, e a espera toda foi inutil. Com o ack
 * implicito do SmallRye, o {@code Uni} devolvido por este metodo completa <i>antes</i> de o
 * pipeline ackear — {@code sair()} rodaria no meio, e o dreno liberaria o desligamento com o
 * ack ainda em voo. Com {@link Acknowledgment.Strategy#MANUAL} o ack entra na cadeia, e o
 * {@code sair()} do {@code eventually} passa a rodar depois dele.
 *
 * <p>O {@code nack} continua passando pelo {@code failure-strategy} do canal exatamente como
 * antes ({@code RabbitMQRequeue} -> {@code basicNack(requeue=true)}): o que muda e quem
 * dispara, nao o que acontece.
 *
 * <p>Com o portao fechado o metodo devolve um {@code Uni} que nunca completa, e a mensagem
 * fica sem ack e sem nack ate o conector fechar o canal e o broker reenfileira-la. Nackear
 * aqui seria pior, nao melhor: gasta a mesma entrega e ainda arrisca comecar o {@code
 * basicNack} no canal que o conector esta fechando.
 */
@ApplicationScoped
public class ExtrairVideoConsumer {

    private static final Logger LOG = Logger.getLogger(ExtrairVideoConsumer.class);

    @Inject
    ExtracaoController extracaoController;

    @Inject
    DrenoDaExtracao dreno;

    @Incoming("extrair-video")
    @Blocking
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<Void> consumir(Message<ExtrairVideo> mensagem) {
        ExtrairVideo comando = mensagem.getPayload();
        if (!dreno.entrar()) {
            LOG.warnf("desligamento em curso; idVideo=%s nao vai comecar e volta para a fila",
                    comando.idVideo());
            return Uni.createFrom().nothing();
        }
        // deferred, e nao completionStage direto: uma excecao lancada de forma sincrona pelo
        // controller escaparia antes de existir cadeia onde pendurar o eventually, e o sair()
        // nunca aconteceria — deixando emVoo em 1 para sempre e fazendo todo desligamento
        // seguinte desta replica esperar o teto inteiro por uma Extracao que nao existe.
        return Uni.createFrom().deferred(() -> Uni.createFrom().completionStage(
                        extracaoController.processarExtrairVideo(
                                comando.idVideo(), comando.chaveVideo(), comando.chaveDestinoPacote())))
                .onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                        falha == null ? mensagem.ack() : mensagem.nack(falha)))
                .eventually(dreno::sair);
    }
}
