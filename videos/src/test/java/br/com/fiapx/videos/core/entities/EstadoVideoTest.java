package br.com.fiapx.videos.core.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstadoVideoTest {

    @Test
    void oGrafoTemUmCaminhoDeIdaESeBifurcaNoFim() {
        assertTrue(EstadoVideo.RECEBIDO.transitaPara(EstadoVideo.PROCESSANDO));
        assertTrue(EstadoVideo.PROCESSANDO.transitaPara(EstadoVideo.CONCLUIDO));
        assertTrue(EstadoVideo.PROCESSANDO.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void oPredecessorEOQueOUseCasePassaAoWhereDoUpdate() {
        assertEquals(EstadoVideo.RECEBIDO, EstadoVideo.PROCESSANDO.predecessor());
        assertEquals(EstadoVideo.PROCESSANDO, EstadoVideo.CONCLUIDO.predecessor());
        assertEquals(EstadoVideo.PROCESSANDO, EstadoVideo.FALHOU.predecessor());
    }

    @Test
    void recebidoEOrigemENaoTemPredecessor() {
        assertThrows(IllegalStateException.class, EstadoVideo.RECEBIDO::predecessor);
    }

    @Test
    void reentregaForaDeOrdemENegadaSemExcecao() {
        // O consumidor da ack nos dois casos; excecao no caminho esperado viraria ruido de log.
        assertFalse(EstadoVideo.PROCESSANDO.transitaPara(EstadoVideo.PROCESSANDO));
        assertFalse(EstadoVideo.RECEBIDO.transitaPara(EstadoVideo.CONCLUIDO));
        assertFalse(EstadoVideo.RECEBIDO.transitaPara(EstadoVideo.FALHOU));
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.RECEBIDO));
    }

    @Test
    void reentregaDoMesmoTerminalENegadaSemExcecao() {
        // Distinto do teste acima: aqui e o MESMO terminal, nao o outro. Tres entregas do
        // mesmo ExtracaoFalhou apos o Vídeo ja estar FALHOU nao podem lancar excecao (ADR 0001).
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.CONCLUIDO));
        assertFalse(EstadoVideo.FALHOU.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void deUmTerminalParaOOutroEBugENaoReentrega() {
        assertThrows(IllegalStateException.class,
                () -> EstadoVideo.FALHOU.transitaPara(EstadoVideo.CONCLUIDO));
        assertThrows(IllegalStateException.class,
                () -> EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void terminalENoFimDaExtracao() {
        assertFalse(EstadoVideo.RECEBIDO.terminal());
        assertFalse(EstadoVideo.PROCESSANDO.terminal());
        assertTrue(EstadoVideo.CONCLUIDO.terminal());
        assertTrue(EstadoVideo.FALHOU.terminal());
    }
}
