package br.com.fiapx.videos.core.entities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotivoFalhaTest {

    @Test
    void codigoConhecidoViraOProprioValor() {
        assertEquals(MotivoFalha.DURACAO_EXCEDIDA, MotivoFalha.doCodigo("DURACAO_EXCEDIDA"));
    }

    @Test
    void codigoDeUmExtracaoMaisNovoNaoDerrubaAMensagem() {
        // A estrategia do contrato de mensagens e aditiva + tolerant reader: um codigo novo
        // tem de pousar em DESCONHECIDO, nunca em excecao.
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo("CODIGO_QUE_AINDA_NAO_EXISTE"));
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo(null));
        assertEquals(MotivoFalha.DESCONHECIDO, MotivoFalha.doCodigo("  "));
    }
}
