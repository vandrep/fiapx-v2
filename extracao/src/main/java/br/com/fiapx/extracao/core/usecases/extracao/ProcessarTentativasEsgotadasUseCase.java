package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.interfaces.sender.ExtracaoEventosSender;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * O consumidor da propria DLQ (docs/contratos/mensagens.md § Dead-letter queues): sem ele o
 * Video trava em PROCESSANDO para sempre. Nao ha o que reprocessar aqui — as tres entregas do
 * {@code x-delivery-limit} ja se esgotaram (ADR 0001) — so publicar a falha definitiva com o
 * unico codigo que este consumidor pode saber: {@code TENTATIVAS_ESGOTADAS}.
 */
public class ProcessarTentativasEsgotadasUseCase {

    private final ExtracaoEventosSender extracaoEventosSender;

    public ProcessarTentativasEsgotadasUseCase(ExtracaoEventosSender extracaoEventosSender) {
        this.extracaoEventosSender = extracaoEventosSender;
    }

    public CompletableFuture<Void> executar(Command command) {
        return extracaoEventosSender.enviarFalhou(
                command.idVideo(), MotivoFalha.TENTATIVAS_ESGOTADAS, command.detalheTecnico(), Instant.now());
    }

    public record Command(UUID idVideo, String detalheTecnico) {
    }
}
