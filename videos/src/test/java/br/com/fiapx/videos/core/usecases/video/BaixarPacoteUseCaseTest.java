package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.exceptions.PacoteExpiradoException;
import br.com.fiapx.videos.core.exceptions.PacoteIndisponivelException;
import br.com.fiapx.videos.core.exceptions.VideoNaoEncontradoException;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaixarPacoteUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");
    private static final Dono OUTRO = new Dono("sub-2", "outro@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.Arquivos arquivos;
    private BaixarPacoteUseCase useCase;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        arquivos = new GatewaysEmMemoria.Arquivos();
        useCase = new BaixarPacoteUseCase(videos, arquivos);
    }

    @Test
    void videoConcluidoComObjetoNoLugarDevolveOFluxo() {
        var video = concluido();
        arquivos.pacotes.put(video.chavePacote(), Multi.createFrom()
                .item(ByteBuffer.wrap("PK".getBytes(StandardCharsets.UTF_8))));

        var pacote = useCase.executar(new BaixarPacoteUseCase.Command(video.id(), DONO)).join();

        assertEquals("ferias.zip", pacote.nomeSugerido());
        assertEquals(900L, pacote.tamanhoBytes());
        assertNotNull(pacote.conteudo());
    }

    @Test
    void videoQueNaoConcluiuEAindaNao_naoNaoMais() {
        // 409: repetir a requisicao faz sentido, a Extracao pode terminar a qualquer momento.
        var video = Video.novo("ferias.mp4", 10L, DONO).armazenadoEm("k");
        videos.armazenados.put(video.id(), video);

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(new BaixarPacoteUseCase.Command(video.id(), DONO)).join());
        assertInstanceOf(PacoteIndisponivelException.class, falha.getCause());
    }

    @Test
    void videoFalhouTambemEIndisponivelENaoExpirado() {
        var video = Video.reconstituir(UUID.randomUUID(), "ferias.mp4", 10L, DONO, "k",
                EstadoVideo.FALHOU, Instant.EPOCH, Instant.EPOCH, null, null, null,
                MotivoFalha.TENTATIVAS_ESGOTADAS);
        videos.armazenados.put(video.id(), video);

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(new BaixarPacoteUseCase.Command(video.id(), DONO)).join());
        assertInstanceOf(PacoteIndisponivelException.class, falha.getCause());
    }

    @Test
    void videoConcluidoComObjetoAusenteExpirou() {
        // 410: a Extracao concluiu, o objeto sumiu. Insistir nao adianta.
        var video = concluido();

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(new BaixarPacoteUseCase.Command(video.id(), DONO)).join());
        assertInstanceOf(PacoteExpiradoException.class, falha.getCause());
    }

    @Test
    void oEstadoDoVideoNaoMudaAoDescobrirQueOPacoteSumiu() {
        // A tabela `video` e o registro do que aconteceu, nao um espelho do bucket.
        var video = concluido();

        assertThrows(CompletionException.class,
                () -> useCase.executar(new BaixarPacoteUseCase.Command(video.id(), DONO)).join());

        var depois = videos.armazenados.get(video.id());
        assertEquals(EstadoVideo.CONCLUIDO, depois.estado());
        assertNotNull(depois.chavePacote());
    }

    @Test
    void pacoteDeVideoAlheioENaoEncontrado() {
        var video = concluido();

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(new BaixarPacoteUseCase.Command(video.id(), OUTRO)).join());
        assertInstanceOf(VideoNaoEncontradoException.class, falha.getCause());
    }

    private Video concluido() {
        var video = Video.novo("ferias.mp4", 10L, DONO).armazenadoEm("k");
        video.marcaComoIniciada();
        video.marcaComoConcluida(Instant.parse("2026-08-21T14:05:47Z"), video.id() + ".zip", 1200, 900L);
        videos.armazenados.put(video.id(), video);
        return video;
    }
}
