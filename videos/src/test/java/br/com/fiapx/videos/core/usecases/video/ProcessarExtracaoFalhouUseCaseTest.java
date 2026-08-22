package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A guarda de unicidade do e-mail (ADR 0001): tres entregas do mesmo {@code ExtracaoFalhou}
 * devem produzir exatamente um {@code VideoFalhou}, porque so a transicao que de fato mudou
 * a linha publica.
 */
class ProcessarExtracaoFalhouUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.NotificacaoEnvios notificacao;
    private ProcessarExtracaoFalhouUseCase useCase;
    private Video video;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        notificacao = new GatewaysEmMemoria.NotificacaoEnvios();
        useCase = new ProcessarExtracaoFalhouUseCase(videos, notificacao);

        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
        video.marcaComoIniciada();
        videos.armazenados.put(video.id(), video);
    }

    @Test
    void tresEntregasDoMesmoEventoProduzemUmUnicoVideoFalhou() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();
        useCase.executar(comando).join();
        useCase.executar(comando).join();

        assertEquals(1, notificacao.idsEnviados.size());
        assertEquals(EstadoVideo.FALHOU, video.estado());
    }

    @Test
    void aPrimeiraEntregaMarcaAFalhaComoPublicada() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.FORMATO_NAO_SUPORTADO, Instant.now());

        useCase.executar(comando).join();

        assertNotNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void umEventoParaUmVideoQueNuncaExistiuNaoPublicaNada() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                java.util.UUID.randomUUID(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();

        assertEquals(0, notificacao.idsEnviados.size());
    }
}
