package br.com.fiapx.extracao.core.entities;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Decide o desfecho da Extração a partir dos sinais produzidos por {@code ffmpeg} e
 * {@code ffprobe}. Os processos pertencem à infraestrutura; o significado dos resultados
 * pertence ao domínio.
 */
public final class CicloDaExtracao {

    private CicloDaExtracao() {
    }

    public static DecisaoFalha classificarFalhaDoFfmpeg(SinaisDoFfmpeg sinais) {
        Objects.requireNonNull(sinais, "sinais");
        var exitCode = sinais.exitCode();
        var stderr = sinais.stderr().toLowerCase(Locale.ROOT);
        var ultimaLinha = stderr.lines().reduce((anterior, atual) -> atual).orElse("");

        if (exitCode == 183) {
            return DecisaoFalha.permanente(MotivoFalha.ARQUIVO_INVALIDO);
        }
        if (exitCode == 8 && (ultimaLinha.contains("unknown encoder")
                || ultimaLinha.contains("unknown decoder")
                || ultimaLinha.matches(".*decoder.*not found.*"))) {
            return DecisaoFalha.permanente(MotivoFalha.FORMATO_NAO_SUPORTADO);
        }
        if (exitCode == 234 && stderr.contains("does not contain any stream")) {
            return DecisaoFalha.permanente(MotivoFalha.SEM_FLUXO_DE_VIDEO);
        }
        return DecisaoFalha.transitoria();
    }

    public static Optional<MotivoFalha> motivoSeSondagemFalhou(int exitCode) {
        return exitCode == 0 ? Optional.empty() : Optional.of(MotivoFalha.ARQUIVO_INVALIDO);
    }

    public static Optional<MotivoFalha> motivoAoValidarFluxoDeVideo(boolean temFluxoDeVideo) {
        return temFluxoDeVideo ? Optional.empty() : Optional.of(MotivoFalha.SEM_FLUXO_DE_VIDEO);
    }

    public static Optional<MotivoFalha> motivoAoValidarDuracao(Duration duracao, Duration tetoDuracao) {
        Objects.requireNonNull(duracao, "duracao");
        Objects.requireNonNull(tetoDuracao, "tetoDuracao");
        return duracao.compareTo(tetoDuracao) > 0
                ? Optional.of(MotivoFalha.DURACAO_EXCEDIDA)
                : Optional.empty();
    }

    public static Optional<MotivoFalha> motivoAoValidarContagemDeFrames(int quantidadeFrames,
                                                                         Duration duracao) {
        Objects.requireNonNull(duracao, "duracao");
        var esperado = Math.max(1, Math.round(duracao.toMillis() / 1000.0));
        return quantidadeFrames < esperado * 0.9
                ? Optional.of(MotivoFalha.ARQUIVO_INVALIDO)
                : Optional.empty();
    }

    public record DecisaoFalha(Optional<MotivoFalha> motivoPermanente) {

        public DecisaoFalha {
            Objects.requireNonNull(motivoPermanente, "motivoPermanente");
        }

        public static DecisaoFalha permanente(MotivoFalha motivo) {
            return new DecisaoFalha(Optional.of(motivo));
        }

        public static DecisaoFalha transitoria() {
            return new DecisaoFalha(Optional.empty());
        }

    }

    public record SinaisDoFfmpeg(int exitCode, String stderr) {

        public SinaisDoFfmpeg {
            stderr = stderr == null ? "" : stderr;
        }
    }
}
