package br.com.fiapx.videos.core.entities;

import org.junit.jupiter.api.Test;

import java.util.Set;

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
    void oTerminalTambemSaiDiretoDeRecebido() {
        // ExtracaoIniciada e ExtracaoConcluida vem em filas independentes e sem ordem entre
        // si (ticket 027, defeito 1). A terminal que chega primeiro tem de casar RECEBIDO,
        // ou o Video fica preso em PROCESSANDO com o .zip ja gravado no bucket.
        assertTrue(EstadoVideo.RECEBIDO.transitaPara(EstadoVideo.CONCLUIDO));
        assertTrue(EstadoVideo.RECEBIDO.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void osPredecessoresSaoOQueOUseCasePassaAoWhereDoUpdate() {
        assertEquals(Set.of(EstadoVideo.RECEBIDO), EstadoVideo.PROCESSANDO.predecessores());
        assertEquals(Set.of(EstadoVideo.RECEBIDO, EstadoVideo.PROCESSANDO),
                EstadoVideo.CONCLUIDO.predecessores());
        assertEquals(Set.of(EstadoVideo.RECEBIDO, EstadoVideo.PROCESSANDO),
                EstadoVideo.FALHOU.predecessores());
    }

    @Test
    void recebidoEOrigemENaoTemPredecessor() {
        assertThrows(IllegalStateException.class, EstadoVideo.RECEBIDO::predecessores);
    }

    @Test
    void reentregaForaDeOrdemENegadaSemExcecao() {
        // O consumidor da ack nos dois casos; excecao no caminho esperado viraria ruido de log.
        assertFalse(EstadoVideo.PROCESSANDO.transitaPara(EstadoVideo.PROCESSANDO));
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.RECEBIDO));
        // A Iniciada que chega DEPOIS da terminal: nao casa nada, e o consumidor da ack.
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.PROCESSANDO));
        assertFalse(EstadoVideo.FALHOU.transitaPara(EstadoVideo.PROCESSANDO));
    }

    @Test
    void reentregaDoMesmoTerminalENegadaSemExcecao() {
        // Distinto do teste acima: aqui e o MESMO terminal, nao o outro. Tres entregas do
        // mesmo ExtracaoFalhou apos o Vídeo ja estar FALHOU nao podem lancar excecao (ADR 0001).
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.CONCLUIDO));
        assertFalse(EstadoVideo.FALHOU.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void primeiroTerminalVenceSemExcecao() {
        assertFalse(EstadoVideo.FALHOU.transitaPara(EstadoVideo.CONCLUIDO));
        assertFalse(EstadoVideo.CONCLUIDO.transitaPara(EstadoVideo.FALHOU));
    }

    @Test
    void terminalENoFimDaExtracao() {
        assertFalse(EstadoVideo.RECEBIDO.terminal());
        assertFalse(EstadoVideo.PROCESSANDO.terminal());
        assertTrue(EstadoVideo.CONCLUIDO.terminal());
        assertTrue(EstadoVideo.FALHOU.terminal());
    }
}
