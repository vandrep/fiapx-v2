package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.exceptions.FalhaPermanenteDeExtracaoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Um arquivo que o {@code ffprobe} le sem erro mas que nao tem fluxo de video vira
 * SEM_FLUXO_DE_VIDEO. E o unico caminho da sondagem que nenhum outro teste alcanca: o
 * cenario BDD do arquivo que nao e video para na primeira sondagem, a de duracao, e o
 * SEM_FLUXO_DE_VIDEO de {@code ExtracaoTest} vem da tabela de exit codes do ffmpeg (exit
 * 234), que e outra decisao.
 *
 * <p>Roda contra os binarios de verdade, como o resto do {@code extracao} (AGENTS.md
 * § Rodar): {@code somente-audio.wav} e 1 s de silencio PCM, sem stream de video nenhum.
 */
class SondagemSemFluxoDeVideoTest {

    @Test
    void umArquivoSoDeAudioFalhaPermanentementePorNaoTerFluxoDeVideo(@TempDir Path scratch) throws Exception {
        var adapter = new FfmpegExtracaoDeFramesAdapter();
        adapter.timeoutFfprobeSegundos = 30;
        adapter.timeoutFfmpegSegundos = 300;

        var audio = scratch.resolve("somente-audio.wav");
        try (var recurso = getClass().getResourceAsStream("/fixtures/somente-audio.wav")) {
            Files.copy(recurso, audio);
        }
        var trabalho = Files.createDirectory(scratch.resolve("trabalho"));

        var erro = assertThrows(ExecutionException.class,
                () -> adapter.processar(audio, trabalho, scratch.resolve("pacote.zip"),
                        Duration.ofMinutes(20)).get());

        var falha = assertInstanceOf(FalhaPermanenteDeExtracaoException.class, erro.getCause());
        assertEquals(MotivoFalha.SEM_FLUXO_DE_VIDEO, falha.motivo());
    }
}
