package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessarExtracaoIniciadaUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private ProcessarExtracaoIniciadaUseCase useCase;
    private Video video;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        useCase = new ProcessarExtracaoIniciadaUseCase(videos);
        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
        videos.armazenados.put(video.id(), video);
    }

    @Test
    void recebidoViraProcessando() {
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id(), Instant.now())).join();

        assertEquals(EstadoVideo.PROCESSANDO, video.estado());
    }

    @Test
    void reentregaForaDeOrdemNaoFalha() {
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id(), Instant.now())).join();

        // Ja em PROCESSANDO: a segunda entrega e um no-op, e o consumidor da ack do mesmo jeito.
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id(), Instant.now())).join();

        assertEquals(EstadoVideo.PROCESSANDO, video.estado());
    }
}
