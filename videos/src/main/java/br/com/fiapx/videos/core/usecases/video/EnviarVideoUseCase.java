package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Origem do {@code POST /videos}. Toca tres sistemas sem transacao comum, e a ordem e fixa:
 * <b>objeto no armazenamento -> linha no banco (marca nula) -> publish do ExtrairVideo ->
 * marca gravada</b>, para que nenhum passo referencie algo que ainda nao existe e para que
 * um crash entre publish e marca apenas republique, nunca perca a mensagem (ADR 0003). O
 * publish e a marca em si sao {@link PublicarExtrairVideo}, o mesmo caminho que a
 * reconciliacao usa.
 */
public class EnviarVideoUseCase {

    private final ArquivoGateway arquivoGateway;
    private final VideoGateway videoGateway;
    private final PublicarExtrairVideo publicarExtrairVideo;
    private final VideoPresenter videoPresenter;

    public EnviarVideoUseCase(ArquivoGateway arquivoGateway,
                              VideoGateway videoGateway,
                              PublicarExtrairVideo publicarExtrairVideo,
                              VideoPresenter videoPresenter) {
        this.arquivoGateway = arquivoGateway;
        this.videoGateway = videoGateway;
        this.publicarExtrairVideo = publicarExtrairVideo;
        this.videoPresenter = videoPresenter;
    }

    public CompletableFuture<Video> executar(Command command) {
        FormatoDoArquivo.aceito(command.nome(), command.contentType());

        var video = Video.novo(command.nome(), command.tamanhoBytes(), command.dono());
        return arquivoGateway.gravarVideo(video.id(), video.nome(), command.arquivo())
                .thenApply(video::armazenadoEm)
                .thenCompose(armazenado -> videoGateway.adicionar(armazenado).thenApply(ignorado -> armazenado))
                .thenCompose(armazenado -> publicarExtrairVideo.publicar(armazenado).thenApply(ignorado -> armazenado))
                .thenApply(armazenado -> {
                    videoPresenter.present(VideoDTO.de(armazenado));
                    return armazenado;
                });
    }

    /**
     * @param arquivo o upload ja em disco local; o adapter o envia sem passar por memoria
     */
    public record Command(String nome, String contentType, long tamanhoBytes, Path arquivo, Dono dono) {
    }
}
