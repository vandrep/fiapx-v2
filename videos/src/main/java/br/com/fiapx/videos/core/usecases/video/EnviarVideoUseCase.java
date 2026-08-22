package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Origem do {@code POST /videos}. Toca tres sistemas sem transacao comum, e a ordem e fixa:
 * <b>objeto no armazenamento -> linha no banco (marca nula) -> publish do ExtrairVideo ->
 * marca gravada</b>, para que nenhum passo referencie algo que ainda nao existe e para que
 * um crash entre publish e marca apenas republique, nunca perca a mensagem (ADR 0003).
 */
public class EnviarVideoUseCase {

    private final ArquivoGateway arquivoGateway;
    private final VideoGateway videoGateway;
    private final ExtracaoSender extracaoSender;
    private final VideoPresenter videoPresenter;

    public EnviarVideoUseCase(ArquivoGateway arquivoGateway,
                              VideoGateway videoGateway,
                              ExtracaoSender extracaoSender,
                              VideoPresenter videoPresenter) {
        this.arquivoGateway = arquivoGateway;
        this.videoGateway = videoGateway;
        this.extracaoSender = extracaoSender;
        this.videoPresenter = videoPresenter;
    }

    public CompletableFuture<Video> executar(Command command) {
        FormatoDoArquivo.aceito(command.nome(), command.contentType());

        var video = Video.novo(command.nome(), command.tamanhoBytes(), command.dono());
        return arquivoGateway.gravarVideo(video.id(), video.nome(), command.arquivo())
                .thenApply(video::armazenadoEm)
                .thenCompose(armazenado -> videoGateway.adicionar(armazenado).thenApply(ignorado -> armazenado))
                .thenCompose(this::publicarExtrairVideoEMarcar)
                .thenApply(armazenado -> {
                    videoPresenter.present(VideoDTO.de(armazenado));
                    return armazenado;
                });
    }

    private CompletableFuture<Video> publicarExtrairVideoEMarcar(Video armazenado) {
        var chaveDestinoPacote = arquivoGateway.chaveDoPacote(armazenado.id());
        return extracaoSender
                .enviarExtrairVideo(armazenado.id(), armazenado.chaveVideo(), chaveDestinoPacote)
                .thenCompose(ignorado -> videoGateway.marcarComandoPublicado(armazenado.id(), Instant.now()))
                .thenApply(ignorado -> armazenado);
    }

    /**
     * @param arquivo o upload ja em disco local; o adapter o envia sem passar por memoria
     */
    public record Command(String nome, String contentType, long tamanhoBytes, Path arquivo, Dono dono) {
    }
}
