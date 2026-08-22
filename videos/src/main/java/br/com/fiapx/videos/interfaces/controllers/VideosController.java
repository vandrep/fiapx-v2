package br.com.fiapx.videos.interfaces.controllers;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.usecases.video.BaixarPacoteUseCase;
import br.com.fiapx.videos.core.usecases.video.ConsultarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.EnviarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.ListarVideosDoDonoUseCase;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Traduz requisicao em Command e escolhe o use case. Nao conhece HTTP: o {@code sub} e o
 * {@code email} chegam como String pura, vindos do token, e viram {@link Dono} aqui — a
 * borda nao monta value object e o core nao ve JWT.
 */
public class VideosController {

    private final EnviarVideoUseCase enviarVideoUseCase;
    private final ListarVideosDoDonoUseCase listarVideosDoDonoUseCase;
    private final ConsultarVideoUseCase consultarVideoUseCase;
    private final BaixarPacoteUseCase baixarPacoteUseCase;

    public VideosController(EnviarVideoUseCase enviarVideoUseCase,
                            ListarVideosDoDonoUseCase listarVideosDoDonoUseCase,
                            ConsultarVideoUseCase consultarVideoUseCase,
                            BaixarPacoteUseCase baixarPacoteUseCase) {
        this.enviarVideoUseCase = enviarVideoUseCase;
        this.listarVideosDoDonoUseCase = listarVideosDoDonoUseCase;
        this.consultarVideoUseCase = consultarVideoUseCase;
        this.baixarPacoteUseCase = baixarPacoteUseCase;
    }

    public CompletableFuture<UUID> enviar(EnvioRequest request) {
        var command = new EnviarVideoUseCase.Command(
                request.nome(),
                request.contentType(),
                request.tamanhoBytes(),
                request.arquivo(),
                new Dono(request.sub(), request.email()));
        return enviarVideoUseCase.executar(command).thenApply(Video::id);
    }

    public CompletableFuture<Void> listar(ListagemRequest request) {
        var command = new ListarVideosDoDonoUseCase.Command(
                new Dono(request.sub(), request.email()),
                Optional.ofNullable(request.estado()),
                request.pagina(),
                request.tamanho());
        return listarVideosDoDonoUseCase.executar(command);
    }

    public CompletableFuture<Void> consultar(UUID id, String sub, String email) {
        return consultarVideoUseCase.executar(
                new ConsultarVideoUseCase.Command(id, new Dono(sub, email)));
    }

    public CompletableFuture<BaixarPacoteUseCase.Pacote> baixarPacote(UUID id, String sub, String email) {
        return baixarPacoteUseCase.executar(
                new BaixarPacoteUseCase.Command(id, new Dono(sub, email)));
    }

    public record EnvioRequest(String nome,
                               String contentType,
                               long tamanhoBytes,
                               Path arquivo,
                               String sub,
                               String email) {
    }

    public record ListagemRequest(String sub, String email, EstadoVideo estado, int pagina, int tamanho) {
    }
}
