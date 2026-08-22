package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessarExtracaoConcluidaUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private ProcessarExtracaoConcluidaUseCase useCase;
    private Video video;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        useCase = new ProcessarExtracaoConcluidaUseCase(videos);
        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
        video.marcaComoIniciada();
        videos.armazenados.put(video.id(), video);
    }

    @Test
    void processandoViraConcluido() {
        var comando = new ProcessarExtracaoConcluidaUseCase.Command(
                video.id(), Instant.now(), video.id() + ".zip", 1_200, 4_096L);

        useCase.executar(comando).join();

        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
        assertEquals(video.id() + ".zip", video.chavePacote());
        assertEquals(1_200, video.quantidadeFrames());
    }

    @Test
    void reentregaAposConcluidoNaoFalha() {
        var comando = new ProcessarExtracaoConcluidaUseCase.Command(
                video.id(), Instant.now(), video.id() + ".zip", 1_200, 4_096L);
        useCase.executar(comando).join();

        useCase.executar(comando).join();

        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
    }
}
