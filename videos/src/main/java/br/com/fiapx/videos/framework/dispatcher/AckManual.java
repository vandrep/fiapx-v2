package br.com.fiapx.videos.framework.dispatcher;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.reactive.messaging.Message;

final class AckManual {

    private AckManual() {
    }

    static Uni<Void> comAckManual(Message<?> mensagem, Uni<Void> trabalho) {
        return trabalho.onItemOrFailure().transformToUni((ignorado, falha) -> Uni.createFrom().completionStage(
                falha == null ? mensagem.ack() : mensagem.nack(falha)));
    }
}
