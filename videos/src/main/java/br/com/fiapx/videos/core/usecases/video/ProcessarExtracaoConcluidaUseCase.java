package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoConcluida}: RECEBIDO/PROCESSANDO -> CONCLUIDO. A chave do Pacote volta
 * no proprio evento em vez de ser assumida a partir do comando — o {@code extracao} declara
 * o que de fato gravou (docs/contratos/mensagens.md). Depois do {@code UPDATE}, marca o
 * original como liberado para expirar (ticket 105). Forma comum aos consumidores de evento
 * de extracao em {@link TransicaoDeVideo} (ticket 053).
 */
public class ProcessarExtracaoConcluidaUseCase {

    private final VideoGateway videoGateway;
    private final ArquivoGateway arquivoGateway;

    public ProcessarExtracaoConcluidaUseCase(VideoGateway videoGateway, ArquivoGateway arquivoGateway) {
        this.videoGateway = videoGateway;
        this.arquivoGateway = arquivoGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        return TransicaoDeVideo.processar(videoGateway, command.idVideo(),
                video -> video.marcaComoConcluida(command.resultado()),
                video -> videoGateway.marcarConcluida(command.idVideo(), command.resultado()),
                (video, mudou) -> TransicaoDeVideo.marcarDesfechoDoOriginal(arquivoGateway, video));
    }

    public record Command(UUID idVideo, ResultadoExtracao resultado) {
    }
}
