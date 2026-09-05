package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id())).join();

        assertEquals(EstadoVideo.PROCESSANDO, video.estado());
    }

    @Test
    void compareAndSwapQuePerdeACorridaNaoDesfazOTerminal() {
        // Uma terminal venceu a corrida entre o SELECT e o UPDATE desta: o Video lido ainda
        // diz RECEBIDO e a transicao no dominio passa, mas o UPDATE condicional nao acha mais
        // um predecessor de PROCESSANDO e a linha fica onde estava (ADR 0002).
        videos.outraEntregaVenceACorridaPara(video.id(), EstadoVideo.CONCLUIDO);

        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id())).join();

        assertEquals(EstadoVideo.CONCLUIDO, videos.armazenados.get(video.id()).estado());
        // E a guarda diz nao: o use case descarta o booleano, entao a linha parada sozinha
        // nao distingue um UPDATE que reprovou de um que mentiu.
        assertFalse(videos.marcarIniciada(video.id()).join());
    }

    @Test
    void reentregaForaDeOrdemNaoFalha() {
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id())).join();

        // Ja em PROCESSANDO: a segunda entrega e um no-op, e o consumidor da ack do mesmo jeito.
        useCase.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id())).join();

        assertEquals(EstadoVideo.PROCESSANDO, video.estado());
    }
}
