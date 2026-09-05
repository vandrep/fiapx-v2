package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.interfaces.sender.ExtracaoEventosSender;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica os tres eventos do `extracao` nos canais ligados pela config a {@code
 * fiapx.eventos} (docs/contratos/mensagens.md). O `core` fala em {@link MotivoFalha}; quem
 * converte para o {@code codigoMotivo} em string do contrato e este adapter.
 */
@ApplicationScoped
public class RabbitExtracaoEventosSender implements ExtracaoEventosSender {

    @ConfigProperty(name = "fiapx.extracao.timeout-publicacao-falha-segundos")
    long timeoutPublicacaoFalhaSegundos;

    @Channel("extracao-iniciada")
    MutinyEmitter<ExtracaoIniciada> emitterIniciada;

    @Channel("extracao-concluida")
    MutinyEmitter<ExtracaoConcluida> emitterConcluida;

    @Channel("extracao-falhou")
    MutinyEmitter<ExtracaoFalhou> emitterFalhou;

    @Override
    public CompletableFuture<Void> enviarIniciada(UUID idVideo, Instant iniciadaEm) {
        return emitterIniciada.send(new ExtracaoIniciada(idVideo, iniciadaEm)).subscribeAsCompletionStage();
    }

    @Override
    public CompletableFuture<Void> enviarConcluida(UUID idVideo, String chavePacote, int quantidadeFrames,
                                                    long tamanhoBytes, Instant concluidaEm) {
        return emitterConcluida.send(new ExtracaoConcluida(idVideo, chavePacote, quantidadeFrames, tamanhoBytes, concluidaEm))
                .subscribeAsCompletionStage();
    }

    @Override
    public CompletableFuture<Void> enviarFalhou(UUID idVideo, MotivoFalha motivo, String detalheTecnico,
                                                Instant ocorridoEm) {
        return emitterFalhou.send(new ExtracaoFalhou(idVideo, motivo.name(), detalheTecnico, ocorridoEm))
                // O fechamento do canal por exchange ausente pode deixar o confirm pendente
                // no cliente Vert.x. Sem teto, a entrada nunca recebe nack (ticket 037).
                .ifNoItem().after(Duration.ofSeconds(timeoutPublicacaoFalhaSegundos)).fail()
                .subscribeAsCompletionStage();
    }
}
