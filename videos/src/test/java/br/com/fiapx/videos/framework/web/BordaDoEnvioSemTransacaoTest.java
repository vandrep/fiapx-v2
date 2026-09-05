package br.com.fiapx.videos.framework.web;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cerca do defeito do ticket 040, do lado do chamador. A corrida nao nasceu no adapter:
 * nasceu de uma transacao aberta na <b>borda</b>, que englobava a persistencia e a publicacao
 * do {@code ExtrairVideo} e adiava o commit para depois do publish. Como
 * {@code Panache.withTransaction} se <b>junta</b> a uma transacao ambiente, devolver
 * {@code @WithTransaction} ao {@code VideosResource} reabriria a corrida sem que nenhum teste
 * do adapter percebesse — este aqui percebe.
 *
 * <p>Vale para {@code @WithSession} pelo mesmo motivo do javadoc de
 * {@code VideoDataSourceAdapter}: a sessao abre no adapter, e nao na borda, tambem porque o
 * download devolve {@code RestMulti} e nao pode segurar conexao durante o streaming.
 */
class BordaDoEnvioSemTransacaoTest {

    @Test
    void aBordaNaoAbreTransacaoNemSessaoQueEnglobeAPublicacao() {
        var proibidas = Stream.concat(
                        Arrays.stream(VideosResource.class.getAnnotations()),
                        Arrays.stream(VideosResource.class.getDeclaredMethods())
                                .flatMap(metodo -> Arrays.stream(metodo.getAnnotations())))
                .map(BordaDoEnvioSemTransacaoTest::nomeSimples)
                .filter(nome -> nome.equals("WithTransaction") || nome.equals("WithSession"))
                .toList();

        assertTrue(proibidas.isEmpty(),
                "a transacao do envio pertence ao adapter, para o commit preceder o publish"
                        + " (ticket 040); achei na borda: " + proibidas);
    }

    private static String nomeSimples(Annotation anotacao) {
        return anotacao.annotationType().getSimpleName();
    }
}
