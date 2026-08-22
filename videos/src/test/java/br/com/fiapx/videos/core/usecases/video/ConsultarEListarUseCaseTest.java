package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.exceptions.VideoNaoEncontradoException;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsultarEListarUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");
    private static final Dono OUTRO = new Dono("sub-2", "outro@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.Presenter presenter;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        presenter = new GatewaysEmMemoria.Presenter();
    }

    @Test
    void consultarDevolveARepresentacaoDoVideo() {
        var video = persistido(DONO, "ferias.mp4");
        var useCase = new ConsultarVideoUseCase(videos, presenter);

        useCase.executar(new ConsultarVideoUseCase.Command(video.id(), DONO)).join();

        assertEquals(video.id(), presenter.recebido.id());
        assertEquals("ferias.mp4", presenter.recebido.nome());
        assertEquals(EstadoVideo.RECEBIDO, presenter.recebido.estado());
    }

    @Test
    void videoInexistenteEVideoAlheioDaoOMesmoErro() {
        // 403 confirmaria que aquele id existe; o 404 nao vaza nada.
        var video = persistido(DONO, "ferias.mp4");
        var useCase = new ConsultarVideoUseCase(videos, presenter);

        var doOutro = assertThrows(CompletionException.class, () -> useCase
                .executar(new ConsultarVideoUseCase.Command(video.id(), OUTRO)).join());
        var inexistente = assertThrows(CompletionException.class, () -> useCase
                .executar(new ConsultarVideoUseCase.Command(UUID.randomUUID(), DONO)).join());

        assertInstanceOf(VideoNaoEncontradoException.class, doOutro.getCause());
        assertInstanceOf(VideoNaoEncontradoException.class, inexistente.getCause());
    }

    @Test
    void aListagemSoVeOsVideosDoDono() {
        persistido(DONO, "meu-1.mp4");
        persistido(DONO, "meu-2.mp4");
        persistido(OUTRO, "dele.mp4");
        var capturada = new PaginaCapturada();

        new ListarVideosDoDonoUseCase(videos, capturada)
                .executar(new ListarVideosDoDonoUseCase.Command(DONO, Optional.empty(), 0, 20))
                .join();

        assertEquals(2, capturada.pagina.total());
        assertEquals(2, capturada.pagina.conteudo().size());
    }

    @Test
    void oTotalEADaConsultaInteiraNaoODaPagina() {
        for (var indice = 0; indice < 5; indice++) {
            persistido(DONO, "video-" + indice + ".mp4");
        }
        var capturada = new PaginaCapturada();

        new ListarVideosDoDonoUseCase(videos, capturada)
                .executar(new ListarVideosDoDonoUseCase.Command(DONO, Optional.empty(), 0, 2))
                .join();

        assertEquals(5, capturada.pagina.total());
        assertEquals(2, capturada.pagina.conteudo().size());
        assertEquals(0, capturada.pagina.pagina());
        assertEquals(2, capturada.pagina.tamanho());
    }

    @Test
    void oFiltroPorEstadoEOpcional() {
        var emAndamento = persistido(DONO, "andando.mp4");
        emAndamento.marcaComoIniciada();
        persistido(DONO, "parado.mp4");
        var capturada = new PaginaCapturada();

        new ListarVideosDoDonoUseCase(videos, capturada)
                .executar(new ListarVideosDoDonoUseCase.Command(
                        DONO, Optional.of(EstadoVideo.PROCESSANDO), 0, 20))
                .join();

        assertEquals(1, capturada.pagina.total());
        assertEquals("andando.mp4", capturada.pagina.conteudo().get(0).nome());
    }

    private Video persistido(Dono dono, String nome) {
        var video = Video.novo(nome, 10L, dono).armazenadoEm("k");
        videos.armazenados.put(video.id(), video);
        return video;
    }

    private static final class PaginaCapturada
            implements br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter {

        Pagina<VideoDTO> pagina;

        @Override
        public void present(Pagina<VideoDTO> pagina) {
            this.pagina = pagina;
        }
    }
}
