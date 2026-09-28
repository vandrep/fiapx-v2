package br.com.fiapx.videos.core.entities;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    @Test
    void videoNovoNasceRecebidoComIdentidadeEInstante() {
        var video = Video.novo("ferias.mp4", 48_213_004L, DONO);

        assertNotNull(video.id());
        assertNotNull(video.recebidoEm());
        assertEquals(EstadoVideo.RECEBIDO, video.estado());
        assertEquals("ferias.mp4", video.nome());
        assertNull(video.finalizadoEm());
        assertNull(video.motivo());
        assertFalse(video.temPacote());
    }

    @Test
    void videoNovoRecusaNomeVazioTamanhoZeroEDonoAusente() {
        assertThrows(IllegalArgumentException.class, () -> Video.novo("  ", 10L, DONO));
        assertThrows(IllegalArgumentException.class, () -> Video.novo("ferias.mp4", 0L, DONO));
        assertThrows(IllegalArgumentException.class, () -> Video.novo("ferias.mp4", 10L, null));
    }

    @Test
    void aChaveChegaDepois_porqueQuemAConstroiPrecisaDoId() {
        var video = Video.novo("ferias.mp4", 10L, DONO);
        assertNull(video.chaveVideo());

        video.armazenadoEm(video.id() + "/original.mp4");

        assertEquals(video.id() + "/original.mp4", video.chaveVideo());
        assertThrows(IllegalArgumentException.class, () -> video.armazenadoEm(" "));
    }

    @Test
    void asTransicoesRecebemOInstanteDeFora() {
        var concluidaEm = Instant.parse("2026-08-21T14:05:47Z");
        var video = recebido();

        assertTrue(video.marcaComoIniciada(Instant.now()));
        assertTrue(video.marcaComoConcluida(new ResultadoExtracao(concluidaEm, "pac.zip", 1200, 900L)));

        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
        assertEquals(concluidaEm, video.finalizadoEm());
        assertEquals("pac.zip", video.chavePacote());
        assertEquals(1200, video.quantidadeFrames());
        assertEquals(900L, video.tamanhoPacoteBytes());
        assertTrue(video.temPacote());
    }

    @Test
    void aFalhaGuardaOCodigoEOInstante() {
        var falhouEm = Instant.parse("2026-08-21T14:05:47Z");
        var video = recebido();
        video.marcaComoIniciada(Instant.now());

        assertTrue(video.marcaComoFalha(falhouEm, MotivoFalha.DURACAO_EXCEDIDA));

        assertEquals(EstadoVideo.FALHOU, video.estado());
        assertEquals(MotivoFalha.DURACAO_EXCEDIDA, video.motivo());
        assertEquals(falhouEm, video.finalizadoEm());
        assertFalse(video.temPacote());
    }

    @Test
    void transicaoJaAplicadaNaoMudaNadaENaoLevantaExcecao() {
        var video = recebido();
        video.marcaComoIniciada(Instant.now());

        assertFalse(video.marcaComoIniciada(Instant.now()));
        assertEquals(EstadoVideo.PROCESSANDO, video.estado());
    }

    @Test
    void aIniciadaGuardaOInstanteDoEvento() {
        var iniciadaEm = Instant.parse("2026-09-14T10:00:00Z");
        var video = recebido();
        assertNull(video.iniciadaEm());

        assertTrue(video.marcaComoIniciada(iniciadaEm));

        assertEquals(iniciadaEm, video.iniciadaEm());
    }

    @Test
    void reentregaNaoTrocaOInstanteDaPrimeiraTentativa() {
        // O limiar de Video preso conta as tres entregas desde a primeira (ticket 106): se a
        // segunda tentativa reescrevesse o instante, o Video ganharia 30 min a cada reentrega.
        var primeira = Instant.parse("2026-09-14T10:00:00Z");
        var video = recebido();
        video.marcaComoIniciada(primeira);

        assertFalse(video.marcaComoIniciada(primeira.plusSeconds(420)));

        assertEquals(primeira, video.iniciadaEm());
    }

    @Test
    void iniciadaSemInstanteERecusada() {
        // Sem instante, um PROCESSANDO preso seria invisivel ao alerta para sempre.
        assertThrows(IllegalArgumentException.class, () -> recebido().marcaComoIniciada(null));
    }

    @Test
    void concluirSemPassarPorProcessandoConclui() {
        // Este teste ja afirmou o contrario, e afirmava o defeito 1 do ticket 027: pular
        // PROCESSANDO nao e ilegal, e o que acontece quando a ExtracaoConcluida ganha a
        // corrida da ExtracaoIniciada — duas filas independentes, sem ordem entre si.
        var video = recebido();

        assertTrue(video.marcaComoConcluida(new ResultadoExtracao(Instant.now(), "pac.zip", 1, 1L)));
        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
        assertEquals("pac.zip", video.chavePacote());
    }

    @Test
    void falharSemPassarPorProcessandoFalha() {
        var video = recebido();

        assertTrue(video.marcaComoFalha(Instant.now(), MotivoFalha.ARQUIVO_INVALIDO));
        assertEquals(EstadoVideo.FALHOU, video.estado());
    }

    @Test
    void iniciarDepoisDeConcluidoNaoMudaNada() {
        var video = recebido();
        video.marcaComoConcluida(new ResultadoExtracao(Instant.now(), "pac.zip", 1, 1L));

        assertFalse(video.marcaComoIniciada(Instant.now()));
        assertEquals(EstadoVideo.CONCLUIDO, video.estado());
    }

    @Test
    void reconstituirAceitaQualquerEstadoValidoSemRodarInvariantesDeCriacao() {
        var id = UUID.randomUUID();
        var video = Video.reconstituir(id, "ferias.mp4", 10L, DONO, "k", EstadoVideo.FALHOU,
                Instant.EPOCH, null, Instant.EPOCH, null, null, null, MotivoFalha.TENTATIVAS_ESGOTADAS);

        assertEquals(id, video.id());
        assertEquals(EstadoVideo.FALHOU, video.estado());
        assertEquals(MotivoFalha.TENTATIVAS_ESGOTADAS, video.motivo());
    }

    private static Video recebido() {
        return Video.novo("ferias.mp4", 10L, DONO).armazenadoEm("chave");
    }
}
