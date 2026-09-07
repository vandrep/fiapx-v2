package br.com.fiapx.extracao.core.entities;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtracaoTest {

    @ParameterizedTest(name = "exit {0} resulta em {2}")
    @MethodSource("falhasDoFfmpeg")
    void classificaFalhaDoFfmpeg(int exitCode, String stderr, Extracao.DecisaoFalha esperada) {
        var sinais = new Extracao.SinaisDoFfmpeg(exitCode, stderr);
        assertEquals(esperada, Extracao.classificarFalhaDoFfmpeg(sinais));
    }

    static Stream<Arguments> falhasDoFfmpeg() {
        return Stream.of(
                Arguments.of(183, "Invalid data found when processing input",
                        Extracao.DecisaoFalha.permanente(MotivoFalha.ARQUIVO_INVALIDO)),
                Arguments.of(8, "Unknown encoder 'png'",
                        Extracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(8, "Unknown decoder 'h264'",
                        Extracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(8, "Decoder h264 not found",
                        Extracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(234, "Output file does not contain any stream\n"
                                + "Error opening output file frames/frame_%04d.png\n"
                                + "Error opening output files: Invalid argument",
                        Extracao.DecisaoFalha.permanente(MotivoFalha.SEM_FLUXO_DE_VIDEO)),
                Arguments.of(8, "Unknown decoder 'h264'\nProtocol not found",
                        Extracao.DecisaoFalha.transitoria()),
                Arguments.of(8, "Protocol not found", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(234, "Invalid argument", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(254, "No such file or directory", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(228, "No space left on device", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(251, "Input/output error", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(244, "Cannot allocate memory", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(137, "", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(255, "", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(69, "Error rate exceeded", Extracao.DecisaoFalha.transitoria()),
                Arguments.of(1, "erro desconhecido", Extracao.DecisaoFalha.transitoria()));
    }

    @ParameterizedTest
    @MethodSource("duracoes")
    void decideSeADuracaoExcedeOTeto(Duration duracao, Optional<MotivoFalha> esperado) {
        assertEquals(esperado, Extracao.motivoAoValidarDuracao(duracao, Duration.ofMinutes(20)));
    }

    static Stream<Arguments> duracoes() {
        return Stream.of(
                Arguments.of(Duration.ZERO, Optional.empty()),
                Arguments.of(Duration.ofSeconds(30), Optional.empty()),
                Arguments.of(Duration.ofMinutes(20), Optional.empty()),
                Arguments.of(Duration.ofMinutes(20).plusMillis(1),
                        Optional.of(MotivoFalha.DURACAO_EXCEDIDA)));
    }

    @ParameterizedTest
    @MethodSource("contagensDeFrames")
    void decideSeAContagemDeFramesRepresentaArquivoInvalido(
            int quantidadeFrames, Duration duracao, Optional<MotivoFalha> esperado) {
        assertEquals(esperado, Extracao.motivoAoValidarContagemDeFrames(quantidadeFrames, duracao));
    }

    static Stream<Arguments> contagensDeFrames() {
        return Stream.of(
                Arguments.of(89, Duration.ofSeconds(100), Optional.of(MotivoFalha.ARQUIVO_INVALIDO)),
                Arguments.of(90, Duration.ofSeconds(100), Optional.empty()),
                Arguments.of(1, Duration.ofMillis(500), Optional.empty()),
                Arguments.of(0, Duration.ofMillis(500), Optional.of(MotivoFalha.ARQUIVO_INVALIDO)));
    }
}
