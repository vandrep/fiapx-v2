package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tabela {@code video} e o outbox (ADR 0003): sem broker, prova que a varredura republica
 * o que ficou pendente, marca o que republicou, e nunca toca no que ja foi marcado.
 */
class ReconciliarPublicacoesPendentesUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.Arquivos arquivos;
    private GatewaysEmMemoria.ExtracaoEnvios extracao;
    private GatewaysEmMemoria.NotificacaoEnvios notificacao;
    private ReconciliarPublicacoesPendentesUseCase useCase;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        arquivos = new GatewaysEmMemoria.Arquivos();
        extracao = new GatewaysEmMemoria.ExtracaoEnvios();
        notificacao = new GatewaysEmMemoria.NotificacaoEnvios();
        useCase = new ReconciliarPublicacoesPendentesUseCase(videos,
                new PublicarExtrairVideo(arquivos, extracao, videos),
                new PublicarVideoFalhou(notificacao, videos));
    }

    @Test
    void umComandoPendenteEVelhoERepublicadoEMarcado() {
        var video = recebidoHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);

        useCase.executar().join();

        assertEquals(1, extracao.idsEnviados.size());
        assertEquals(video.id(), extracao.idsEnviados.get(0));
        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
    }

    @Test
    void publicacaoInterrompidaMantemComandoPendenteParaAProximaVarredura() {
        var video = recebidoHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);
        extracao.falharNoProximoEnvio(new RuntimeException("broker indisponivel"));

        assertThrows(RuntimeException.class, () -> useCase.executar().join());
        assertNull(videos.comandoPublicadoEm.get(video.id()),
                "uma publicacao sem confirmacao nao pode deixar marca de sucesso");

        var recuperacao = useCase.executar().join();

        assertEquals(1, recuperacao.comandos());
        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
        assertEquals(2, extracao.idsEnviados.size());
    }

    /**
     * A contagem devolvida e o que torna a garantia do ADR 0003 <b>observavel</b>: ate o
     * ticket 027 a varredura era muda, e por isso nenhuma medicao (nem a do 025) tinha
     * conseguido flagra-la republicando.
     */
    @Test
    void aVarreduraDizQuantoRepublicou() {
        var video = recebidoHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);

        var primeira = useCase.executar().join();
        var segunda = useCase.executar().join();

        assertEquals(1, primeira.comandos());
        assertEquals(0, primeira.falhas());
        assertTrue(primeira.houveAlgo());
        assertEquals(0, segunda.comandos());
        assertFalse(segunda.houveAlgo(), "passada sem pendencia nao pode aparecer no log");
    }

    @Test
    void aSegundaPassagemNaoRepublicaOComandoJaMarcado() {
        var video = recebidoHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);

        useCase.executar().join();
        useCase.executar().join();

        assertEquals(1, extracao.idsEnviados.size());
    }

    @Test
    void umComandoPendenteMasRecenteNaoERepublicado() {
        // Dentro da folga de 1 minuto contra o crash: pode estar so aguardando o publish
        // do proprio EnviarVideoUseCase terminar.
        var video = recebidoHa(10, ChronoUnit.SECONDS);
        videos.armazenados.put(video.id(), video);

        useCase.executar().join();

        assertEquals(0, extracao.idsEnviados.size());
    }

    @Test
    void umComandoJaMarcadoNaoETocadoPorMaisVelhoQueSeja() {
        var video = recebidoHa(1, ChronoUnit.DAYS);
        videos.armazenados.put(video.id(), video);
        videos.comandoPublicadoEm.put(video.id(), Instant.now());

        useCase.executar().join();

        assertEquals(0, extracao.idsEnviados.size());
    }

    @Test
    void umaFalhaPendenteEVelhaERepublicadaEMarcada() {
        var video = falhouHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);

        useCase.executar().join();

        assertEquals(1, notificacao.idsEnviados.size());
        assertEquals(video.id(), notificacao.idsEnviados.get(0));
        assertNotNull(videos.falhaPublicadaEm.get(video.id()));
    }

    /**
     * A metade da falha respeita a mesma folga que a metade do comando (ticket 050): a
     * janela entre gravar FALHOU e publicar VideoFalhou e a mesma janela do ADR 0003, e sem
     * folga uma varredura que caisse sobre ela duplicaria a notificacao.
     */
    @Test
    void umaFalhaPendenteMasRecenteNaoERepublicada() {
        var video = falhouHa(10, ChronoUnit.SECONDS);
        videos.armazenados.put(video.id(), video);

        var passada = useCase.executar().join();

        assertEquals(0, notificacao.idsEnviados.size());
        assertEquals(0, passada.falhas());
        assertNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void umaFalhaJaMarcadaNaoETocada() {
        var video = falhouHa(2, ChronoUnit.MINUTES);
        videos.armazenados.put(video.id(), video);
        videos.falhaPublicadaEm.put(video.id(), Instant.now());

        useCase.executar().join();

        assertEquals(0, notificacao.idsEnviados.size());
    }

    private static Video recebidoHa(long quantidade, ChronoUnit unidade) {
        var id = UUID.randomUUID();
        return Video.reconstituir(
                id, "ferias.mp4", 1_024L, DONO, id + "/original.mp4", EstadoVideo.RECEBIDO,
                Instant.now().minus(quantidade, unidade), null, null, null, null, null);
    }

    /**
     * O instante que a varredura julga e o {@code finalizadoEm} — a marca de quando a linha
     * virou FALHOU —, e nao o {@code recebidoEm}.
     */
    private static Video falhouHa(long quantidade, ChronoUnit unidade) {
        var id = UUID.randomUUID();
        var falhouEm = Instant.now().minus(quantidade, unidade);
        return Video.reconstituir(
                id, "ferias.mp4", 1_024L, DONO, id + "/original.mp4", EstadoVideo.FALHOU,
                falhouEm.minus(1, ChronoUnit.HOURS), falhouEm, null, null, null,
                MotivoFalha.ARQUIVO_INVALIDO);
    }
}
