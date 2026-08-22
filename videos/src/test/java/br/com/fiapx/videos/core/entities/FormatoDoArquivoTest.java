package br.com.fiapx.videos.core.entities;

import br.com.fiapx.videos.core.exceptions.FormatoNaoSuportadoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormatoDoArquivoTest {

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "avi", "mov", "mkv", "webm"})
    void asCincoExtensoesAceitas(String extensao) {
        assertEquals(extensao, FormatoDoArquivo.aceito("ferias." + extensao, "video/" + extensao).extensao());
    }

    @Test
    void aExtensaoENormalizadaParaMinuscula() {
        assertEquals("mp4", FormatoDoArquivo.aceito("FERIAS.MP4", "video/mp4").extensao());
    }

    @Test
    void extensaoForaDaListaERecusada() {
        assertThrows(FormatoNaoSuportadoException.class,
                () -> FormatoDoArquivo.aceito("relatorio.pdf", "video/mp4"));
        assertThrows(FormatoNaoSuportadoException.class,
                () -> FormatoDoArquivo.aceito("semextensao", "video/mp4"));
    }

    @Test
    void contentTypeForaDeVideoERecusadoAindaComExtensaoBoa() {
        // A validacao pergunta as duas coisas: renomear um pdf para .mp4 nao basta.
        assertThrows(FormatoNaoSuportadoException.class,
                () -> FormatoDoArquivo.aceito("ferias.mp4", "application/pdf"));
        assertThrows(FormatoNaoSuportadoException.class,
                () -> FormatoDoArquivo.aceito("ferias.mp4", null));
    }

    @Test
    void oNomeDoPacoteTrocaAExtensaoPorZip() {
        assertEquals("ferias.zip", FormatoDoArquivo.nomeDoPacotePara("ferias.mp4"));
        assertEquals("ferias.de.2026.zip", FormatoDoArquivo.nomeDoPacotePara("ferias.de.2026.mkv"));
        assertEquals("semponto.zip", FormatoDoArquivo.nomeDoPacotePara("semponto"));
    }
}
