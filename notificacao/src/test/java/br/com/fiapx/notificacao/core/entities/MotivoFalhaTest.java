package br.com.fiapx.notificacao.core.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MotivoFalhaTest {

    @Test
    void codigoConhecidoViraOProprioValor() {
        assertEquals(MotivoFalha.DURACAO_EXCEDIDA, MotivoFalha.doCodigo("DURACAO_EXCEDIDA"));
    }

    @Test
    void codigoDesconhecidoNaoDerrubaAMensagem() {
        // O videos repassa DESCONHECIDO quando ele mesmo nao reconhece o codigo vindo do
        // extracao (ticket 009) — tolerant reader tem que absorver os dois casos.
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo("DESCONHECIDO"));
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo("CODIGO_QUE_AINDA_NAO_EXISTE"));
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo(null));
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo("  "));
    }

    @Test
    void todoMotivoTemUmaFraseNaoVazia() {
        for (MotivoFalha motivo : MotivoFalha.values()) {
            assertFalse(motivo.paraFrase().isBlank(), () -> motivo + " sem frase");
        }
    }
}
