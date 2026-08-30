package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ADR 0003: a sequencia enviar-depois-marcar do lado do evento {@code VideoFalhou}, isolada de
 * ProcessarExtracaoFalhouUseCase e de ReconciliarPublicacoesPendentesUseCase, que a chamavam
 * duplicada.
 */
class PublicarVideoFalhouTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.NotificacaoEnvios notificacao;
    private GatewaysEmMemoria.Videos videos;
    private PublicarVideoFalhou publicador;
    private Video video;

    @BeforeEach
    void montar() {
        notificacao = new GatewaysEmMemoria.NotificacaoEnvios();
        videos = new GatewaysEmMemoria.Videos();
        publicador = new PublicarVideoFalhou(notificacao, videos);

        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
        video.marcaComoIniciada();
        video.marcaComoFalha(Instant.now(), MotivoFalha.ARQUIVO_INVALIDO);
    }

    @Test
    void enviaANotificacaoComDonoNomeMotivoEFinalizadoEm() {
        publicador.publicar(video).join();

        assertEquals(List.of(video.id()), notificacao.idsEnviados);
        assertEquals(video.dono(), notificacao.ultimoDono);
        assertEquals(video.nome(), notificacao.ultimoNomeArquivo);
        assertEquals(video.motivo(), notificacao.ultimoMotivo);
        assertEquals(video.finalizadoEm(), notificacao.ultimoOcorridoEm);
    }

    @Test
    void marcaAFalhaComoPublicadaDepoisDoEnvio() {
        publicador.publicar(video).join();

        assertNotNull(videos.falhaPublicadaEm.get(video.id()));
    }
}
