package br.com.fiapx.notificacao.framework.dispatcher;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

import java.util.function.Function;

final class AckManual {

    private AckManual() {
    }

    static Uni<Void> comAckManual(Message<?> mensagem, Uni<Void> trabalho) {
        return trabalho.onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                falha == null ? mensagem.ack() : mensagem.nack(falha)));
    }

    static Uni<Void> comAckManual(Message<?> mensagem, Uni<Void> trabalho,
                                  Function<Throwable, Metadata> metadadosDoNack) {
        return trabalho.onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                falha == null ? mensagem.ack() : mensagem.nack(falha, metadadosDoNack.apply(falha))));
    }
}
