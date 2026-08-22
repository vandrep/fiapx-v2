package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoConcluida}: PROCESSANDO -> CONCLUIDO. A chave do Pacote volta
 * no proprio evento em vez de ser assumida a partir do comando — o {@code extracao} declara
 * o que de fato gravou (docs/contratos/mensagens.md).
 */
public class ProcessarExtracaoConcluidaUseCase {

    private final VideoGateway videoGateway;

    public ProcessarExtracaoConcluidaUseCase(VideoGateway videoGateway) {
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.marcarConcluida(
                        command.idVideo(),
                        command.concluidaEm(),
                        command.chavePacote(),
                        command.quantidadeFrames(),
                        command.tamanhoPacoteBytes())
                .thenApply(ignorado -> null);
    }

    public record Command(UUID idVideo,
                          Instant concluidaEm,
                          String chavePacote,
                          int quantidadeFrames,
                          long tamanhoPacoteBytes) {
    }
}
