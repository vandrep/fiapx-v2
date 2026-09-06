package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoConcluida}: RECEBIDO/PROCESSANDO -> CONCLUIDO. A chave do Pacote volta
 * no proprio evento em vez de ser assumida a partir do comando — o {@code extracao} declara
 * o que de fato gravou (docs/contratos/mensagens.md).
 */
public class ProcessarExtracaoConcluidaUseCase {

    private final VideoGateway videoGateway;

    public ProcessarExtracaoConcluidaUseCase(VideoGateway videoGateway) {
        this.videoGateway = videoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.buscarPorId(command.idVideo())
                .thenCompose(video -> {
                    if (video.isEmpty() || !video.get().marcaComoConcluida(command.resultado())) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return videoGateway.marcarConcluida(command.idVideo(), command.resultado());
                })
                .thenApply(mudou -> null);
    }

    public record Command(UUID idVideo, ResultadoExtracao resultado) {
    }
}
