package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ADR 0003: a sequencia enviar-depois-marcar do lado do comando {@code ExtrairVideo}, isolada
 * de EnviarVideoUseCase e de ReconciliarPublicacoesPendentesUseCase, que a chamavam duplicada.
 */
class PublicarExtrairVideoTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Arquivos arquivos;
    private GatewaysEmMemoria.ExtracaoEnvios extracao;
    private GatewaysEmMemoria.Videos videos;
    private PublicarExtrairVideo publicador;
    private Video video;

    @BeforeEach
    void montar() {
        arquivos = new GatewaysEmMemoria.Arquivos();
        extracao = new GatewaysEmMemoria.ExtracaoEnvios();
        videos = new GatewaysEmMemoria.Videos();
        publicador = new PublicarExtrairVideo(arquivos, extracao, videos);
        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
    }

    @Test
    void enviaOComandoComAChaveDoVideoEAChaveDoPacote() {
        publicador.publicar(video).join();

        assertEquals(List.of(video.id()), extracao.idsEnviados);
        assertEquals(video.chaveVideo(), extracao.ultimaChaveVideo);
        assertEquals(arquivos.chaveDoPacote(video.id()), extracao.ultimaChaveDestinoPacote);
    }

    @Test
    void marcaOComandoComoPublicadoDepoisDoEnvio() {
        publicador.publicar(video).join();

        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
    }
}
