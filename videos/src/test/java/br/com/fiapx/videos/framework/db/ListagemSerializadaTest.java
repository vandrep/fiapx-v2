package br.com.fiapx.videos.framework.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cerca do ticket 045, e ela le o fonte de proposito. A pagina e a contagem sao duas
 * consultas na <b>mesma</b> sessao reativa: combinadas com {@code Uni.combine().all()} elas
 * ficam em voo ao mesmo tempo, que e a corrupcao de sessao que o ticket 017 ja tinha visto
 * em outro fluxo.
 *
 * <p>Nenhuma asserção sobre resultado denuncia a volta do defeito — o
 * {@link br.com.fiapx.videos.framework.web.ListagemConcorrenteTest} passa com as duas
 * formas, e a rajada nao reproduziu a falha. O que distingue a versao correta da errada e a
 * forma da cadeia, entao e a forma que este teste guarda.
 */
class ListagemSerializadaTest {

    private static final Path ADAPTER =
            Path.of("src/main/java/br/com/fiapx/videos/framework/db/VideoDataSourceAdapter.java");

    @Test
    void aListagemNaoCombinaPaginaEContagemNaMesmaSessao() throws IOException {
        var listagem = codigoDeListarPorDono();

        assertTrue(listagem.contains(".count()"),
                "a listagem perdeu a contagem; o total nao pode virar conteudo.size()");
        assertFalse(listagem.contains("Uni.combine"),
                "pagina e contagem rodam na mesma sessao reativa e precisam ser encadeadas,"
                        + " nao combinadas (ticket 045)");
        assertTrue(listagem.contains(".flatMap("),
                "sem o flatMap nao ha encadeamento: a contagem tem de comecar depois da pagina");
    }

    /**
     * Do cabecalho de {@code listarPorDono} ate o proximo metodo do adapter, <b>sem
     * comentarios</b>: o teste julga o codigo, e um comentario que cite {@code Uni.combine}
     * ao explicar por que ela saiu nao pode deixar o build vermelho.
     */
    private static String codigoDeListarPorDono() throws IOException {
        var fonte = Files.readString(ADAPTER);
        var inicio = fonte.indexOf("listarPorDono(Dono dono");
        assertTrue(inicio >= 0, () -> "nao achei listarPorDono em " + ADAPTER.toAbsolutePath());
        var fim = fonte.indexOf("@Override", inicio);
        return semComentarios(fonte.substring(inicio, fim < 0 ? fonte.length() : fim));
    }

    private static String semComentarios(String codigo) {
        return codigo.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
