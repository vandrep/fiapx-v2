package br.com.fiapx.extracao.core.entities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CicloDaExtracaoTest {

    @ParameterizedTest(name = "exit {0} resulta em {2}")
    @MethodSource("falhasDoFfmpeg")
    void classificaFalhaDoFfmpeg(int exitCode, String stderr, CicloDaExtracao.DecisaoFalha esperada) {
        var sinais = new CicloDaExtracao.SinaisDoFfmpeg(exitCode, stderr);
        assertEquals(esperada, CicloDaExtracao.classificarFalhaDoFfmpeg(sinais));
    }

    static Stream<Arguments> falhasDoFfmpeg() {
        return Stream.of(
                Arguments.of(183, "Invalid data found when processing input",
                        CicloDaExtracao.DecisaoFalha.permanente(MotivoFalha.ARQUIVO_INVALIDO)),
                Arguments.of(8, "Unknown encoder 'png'",
                        CicloDaExtracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(8, "Unknown decoder 'h264'",
                        CicloDaExtracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(8, "Decoder h264 not found",
                        CicloDaExtracao.DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO)),
                Arguments.of(234, "Output file does not contain any stream\n"
                                + "Error opening output file frames/frame_%04d.png\n"
                                + "Error opening output files: Invalid argument",
                        CicloDaExtracao.DecisaoFalha.permanente(MotivoFalha.SEM_FLUXO_DE_VIDEO)),
                Arguments.of(8, "Unknown decoder 'h264'\nProtocol not found",
                        CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(8, "Protocol not found", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(234, "Invalid argument", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(254, "No such file or directory", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(228, "No space left on device", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(251, "Input/output error", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(244, "Cannot allocate memory", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(137, "", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(255, "", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(69, "Error rate exceeded", CicloDaExtracao.DecisaoFalha.transitoria()),
                Arguments.of(1, "erro desconhecido", CicloDaExtracao.DecisaoFalha.transitoria()));
    }

    @ParameterizedTest
    @MethodSource("sondagens")
    void decideMotivoPermanenteDaSondagem(int exitCode, boolean temFluxoDeVideo,
                                           Duration duracao, Optional<MotivoFalha> esperado) {
        var motivo = CicloDaExtracao.motivoSeSondagemFalhou(exitCode)
                .or(() -> CicloDaExtracao.motivoAoValidarFluxoDeVideo(temFluxoDeVideo))
                .or(() -> CicloDaExtracao.motivoAoValidarDuracao(duracao, Duration.ofMinutes(20)));
        assertEquals(esperado, motivo);
    }

    static Stream<Arguments> sondagens() {
        return Stream.of(
                Arguments.of(1, false, Duration.ZERO, Optional.of(MotivoFalha.ARQUIVO_INVALIDO)),
                Arguments.of(0, false, Duration.ofSeconds(30), Optional.of(MotivoFalha.SEM_FLUXO_DE_VIDEO)),
                Arguments.of(0, true, Duration.ofMinutes(20), Optional.empty()),
                Arguments.of(0, true, Duration.ofMinutes(20).plusMillis(1),
                        Optional.of(MotivoFalha.DURACAO_EXCEDIDA)));
    }

    @ParameterizedTest
    @MethodSource("contagensDeFrames")
    void decideSeAContagemDeFramesRepresentaArquivoInvalido(
            int quantidadeFrames, Duration duracao, Optional<MotivoFalha> esperado) {
        assertEquals(esperado, CicloDaExtracao.motivoAoValidarContagemDeFrames(quantidadeFrames, duracao));
    }

    static Stream<Arguments> contagensDeFrames() {
        return Stream.of(
                Arguments.of(89, Duration.ofSeconds(100), Optional.of(MotivoFalha.ARQUIVO_INVALIDO)),
                Arguments.of(90, Duration.ofSeconds(100), Optional.empty()),
                Arguments.of(1, Duration.ofMillis(500), Optional.empty()),
                Arguments.of(0, Duration.ofMillis(500), Optional.of(MotivoFalha.ARQUIVO_INVALIDO)));
    }
}
