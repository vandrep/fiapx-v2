package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.exceptions.PacoteExpiradoException;
import br.com.fiapx.videos.core.exceptions.PacoteIndisponivelException;
import br.com.fiapx.videos.core.exceptions.VideoNaoEncontradoException;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Origem do {@code GET /videos/{id}/pacote}, e o unico use case <b>sem presenter</b>:
 * bufferizar bytes num campo privado contraria o "nunca toBytes" do ticket 005. Ele devolve
 * o fluxo, e a borda o adapta.
 *
 * <p>Tres recusas, tres sentidos distintos:
 * <ul>
 *   <li>404 — o Video nao existe, ou nao e seu;
 *   <li>409 — <b>ainda nao</b>: a Extracao nao terminou, e repetir a requisicao faz sentido;
 *   <li>410 — <b>nao mais</b>: a Extracao concluiu, mas o objeto ja expirou (7 dias).
 * </ul>
 *
 * <p>A descoberta da ausencia e preguicosa e <b>este caminho nao grava nada</b>: a tabela
 * `video` e o registro do que aconteceu, nao um espelho do bucket (ticket 019).
 */
public class BaixarPacoteUseCase {

    private final VideoGateway videoGateway;
    private final ArquivoGateway arquivoGateway;

    public BaixarPacoteUseCase(VideoGateway videoGateway, ArquivoGateway arquivoGateway) {
        this.videoGateway = videoGateway;
        this.arquivoGateway = arquivoGateway;
    }

    public CompletableFuture<Pacote> executar(Command command) {
        return videoGateway.buscarPorIdEDono(command.id(), command.dono())
                .thenApply(encontrado -> encontrado
                        .orElseThrow(() -> new VideoNaoEncontradoException(command.id())))
                .thenCompose(video -> {
                    if (!video.temPacote()) {
                        throw new PacoteIndisponivelException(video.estado());
                    }
                    return arquivoGateway.abrirPacote(video.chavePacote())
                            .thenApply(conteudo -> new Pacote(
                                    FormatoDoArquivo.nomeDoPacotePara(video.nome()),
                                    video.tamanhoPacoteBytes(),
                                    conteudo.orElseThrow(() -> new PacoteExpiradoException(
                                            "O Pacote do vídeo " + video.id()
                                                    + " não está mais disponível: os Pacotes ficam"
                                                    + " no armazenamento por 7 dias"))));
                });
    }

    public record Command(UUID id, Dono dono) {
    }

    /** O que a borda precisa para montar a resposta, e nada alem. */
    public record Pacote(String nomeSugerido, Long tamanhoBytes, Flow.Publisher<ByteBuffer> conteudo) {
    }
}
