package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void aConcluidaQueChegaAntesDaIniciadaAindaConclui() {
        // Defeito 1 do ticket 027: as duas mensagens vem em filas independentes. Antes desta
        // correcao o UPDATE exigia PROCESSANDO, alterava zero linhas e dava ack — o Video
        // ficava preso em PROCESSANDO para sempre, com o .zip ja gravado no bucket.
        var recemRecebido = Video.novo("chegou-fora-de-ordem.mp4", 2_048L, DONO)
                .armazenadoEm("id/original.mp4");
        videos.armazenados.put(recemRecebido.id(), recemRecebido);
        assertEquals(EstadoVideo.RECEBIDO, recemRecebido.estado());

        useCase.executar(new ProcessarExtracaoConcluidaUseCase.Command(
                recemRecebido.id(), Instant.now(), recemRecebido.id() + ".zip", 900, 2_048L)).join();

        assertEquals(EstadoVideo.CONCLUIDO, recemRecebido.estado());
        assertEquals(recemRecebido.id() + ".zip", recemRecebido.chavePacote());
    }

    @Test
    void compareAndSwapQuePerdeACorridaNaoGravaOPacote() {
        // A ExtracaoFalhou venceu a corrida entre o SELECT e o UPDATE desta: o Video lido
        // ainda diz PROCESSANDO, entao so o UPDATE condicional reprova. A linha continua
        // FALHOU, e sem chave de Pacote — entre terminais, o primeiro vence (ADR 0002).
        videos.outraEntregaVenceACorridaPara(video.id(), EstadoVideo.FALHOU);

        useCase.executar(new ProcessarExtracaoConcluidaUseCase.Command(
                video.id(), Instant.now(), video.id() + ".zip", 1_200, 4_096L)).join();

        var linha = videos.armazenados.get(video.id());
        assertEquals(EstadoVideo.FALHOU, linha.estado());
        assertNull(linha.chavePacote());
        // E a guarda diz nao: o use case descarta o booleano, entao a linha parada sozinha
        // nao distingue um UPDATE que reprovou de um que mentiu.
        assertFalse(videos.marcarConcluida(video.id(), Instant.now(), video.id() + ".zip", 1_200, 4_096L).join());
    }

    @Test
    void aIniciadaAtrasadaNaoDesfazOConcluido() {
        var iniciada = new ProcessarExtracaoIniciadaUseCase(videos);
        useCase.executar(new ProcessarExtracaoConcluidaUseCase.Command(
                video.id(), Instant.now(), video.id() + ".zip", 1_200, 4_096L)).join();

        iniciada.executar(new ProcessarExtracaoIniciadaUseCase.Command(video.id())).join();

        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
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
