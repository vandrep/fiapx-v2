package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Origem do {@code POST /videos}. Toca tres sistemas sem transacao comum, e a ordem e fixa:
 * <b>objeto no armazenamento -> linha no banco (marca nula) -> publish do ExtrairVideo ->
 * marca gravada</b>, para que nenhum passo referencie algo que ainda nao existe e para que
 * um crash entre publish e marca apenas republique, nunca perca a mensagem (ADR 0003). O
 * publish e a marca em si sao {@link PublicarExtrairVideo}, o mesmo caminho que a
 * reconciliacao usa.
 *
 * <p><b>O aceite e o commit da linha</b> (ticket 104). Falha no armazenamento ou no
 * {@code INSERT} falha o envio — e a do {@code INSERT} tenta apagar o original que ficou sem
 * linha (ticket 105); depois do commit, a varredura do ADR 0003 ja garante o
 * comando, entao o publish que falha ou que nao responde dentro de {@code tetoDoPublish} nao
 * falha a requisicao: o Video sai aceito, com a marca nula, e a varredura publica depois. O
 * teto limita quanto a requisicao <b>espera</b>, nao o publish: confirmado tarde, ele ainda
 * grava a marca, e a varredura nao dobra a Extracao.
 */
public class EnviarVideoUseCase {

    private final ArquivoGateway arquivoGateway;
    private final VideoGateway videoGateway;
    private final PublicarExtrairVideo publicarExtrairVideo;
    private final VideoPresenter videoPresenter;
    private final Duration tetoDoPublish;

    public EnviarVideoUseCase(ArquivoGateway arquivoGateway,
                              VideoGateway videoGateway,
                              PublicarExtrairVideo publicarExtrairVideo,
                              VideoPresenter videoPresenter,
                              Duration tetoDoPublish) {
        this.arquivoGateway = arquivoGateway;
        this.videoGateway = videoGateway;
        this.publicarExtrairVideo = publicarExtrairVideo;
        this.videoPresenter = videoPresenter;
        this.tetoDoPublish = tetoDoPublish;
    }

    public CompletableFuture<Video> executar(Command command) {
        FormatoDoArquivo.aceito(command.nome(), command.contentType());

        var video = Video.novo(command.nome(), command.tamanhoBytes(), command.dono());
        return arquivoGateway.gravarVideo(video.id(), video.nome(), command.arquivo())
                .thenApply(video::armazenadoEm)
                .thenCompose(this::adicionarOuApagarOOriginal)
                .thenApply(aceito -> {
                    videoPresenter.present(VideoDTO.de(aceito));
                    return aceito;
                })
                .thenCompose(aceito -> publicarSemSegurarOAceite(aceito).thenApply(ignorado -> aceito));
    }

    /**
     * O {@code INSERT} que falha deixaria o original sem linha: sem Video, ninguem o marca, e
     * ele nunca expiraria (ticket 105). A limpeza e de melhor esforco e nunca troca a falha do
     * envio pela dela.
     *
     * <p><b>So apaga o que confirmou estar sem linha.</b> A falha do {@code INSERT} pode ser
     * ambigua — a conexao que cai depois do {@code COMMIT} chegar ao servidor —, e a linha
     * commitada ja e um Video aceito, que a varredura do ADR 0003 vai publicar. Apagar o
     * original dele seria Video perdido na terceira forma do glossario. Por isso a busca vem
     * antes, e a busca que falha tambem deixa o original: no pior caso, vazamento.
     */
    private CompletableFuture<Video> adicionarOuApagarOOriginal(Video armazenado) {
        return videoGateway.adicionar(armazenado)
                .thenApply(ignorado -> armazenado)
                .exceptionallyCompose(falhaDoInsert -> apagarOOriginalSemLinha(armazenado)
                        .thenCompose(ignorado -> CompletableFuture.<Video>failedFuture(falhaDoInsert)));
    }

    private CompletableFuture<Void> apagarOOriginalSemLinha(Video armazenado) {
        return videoGateway.buscarPorId(armazenado.id())
                .thenCompose(linha -> linha.isEmpty()
                        ? arquivoGateway.apagarOriginal(armazenado.chaveVideo())
                        : CompletableFuture.<Void>completedFuture(null))
                .exceptionally(limpezaQueFalhou -> null);
    }

    /**
     * O publish visto de quem ja aceitou o Video: nunca falha, e completa no maximo em
     * {@code tetoDoPublish}. O {@code copy()} e o que deixa o publish seguir depois do teto —
     * o timeout completa a copia, e o {@code thenCompose} da marca dentro de
     * {@link PublicarExtrairVideo} continua pendurado no envio original.
     */
    private CompletableFuture<Void> publicarSemSegurarOAceite(Video aceito) {
        return publicarExtrairVideo.publicar(aceito)
                .copy()
                .orTimeout(tetoDoPublish.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(publishSemConfirmacao -> null);
    }

    /**
     * @param arquivo o upload ja em disco local; o adapter o envia sem passar por memoria
     */
    public record Command(String nome, String contentType, long tamanhoBytes, Path arquivo, Dono dono) {
    }
}
