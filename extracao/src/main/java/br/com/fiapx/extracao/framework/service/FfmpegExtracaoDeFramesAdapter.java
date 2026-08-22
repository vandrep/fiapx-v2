package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.entities.ResultadoExtracao;
import br.com.fiapx.extracao.core.exceptions.FalhaPermanenteDeExtracaoException;
import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ExtracaoDeFramesGateway;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * O pipeline de ffmpeg como processo externo (ticket 006, docs/pesquisa/ffmpeg-extracao.md):
 * mede duracao e stream de video com {@code ffprobe}, extrai frames a 1 fps com {@code
 * ffmpeg -xerror}, confere a contagem contra a duracao, e empacota em ZIP {@code STORED}
 * (deflate nao comprime PNG, medido).
 *
 * <p>Todo o pipeline bloqueante roda explicitamente em {@link Infrastructure#getDefaultWorkerPool()},
 * nao no thread que chama {@link #processar}: quem chama pode ser o thread do SDK da AWS que
 * completou o download do MinIO, nao a event loop nem o worker pool do {@code @Blocking} do
 * consumidor — o adapter nao pode confiar no contexto de quem o invoca.
 */
@ApplicationScoped
public class FfmpegExtracaoDeFramesAdapter implements ExtracaoDeFramesGateway {

    private static final Logger LOG = Logger.getLogger(FfmpegExtracaoDeFramesAdapter.class);

    @ConfigProperty(name = "fiapx.extracao.timeout-ffprobe-segundos", defaultValue = "30")
    long timeoutFfprobeSegundos;

    @ConfigProperty(name = "fiapx.extracao.timeout-ffmpeg-segundos", defaultValue = "300")
    long timeoutFfmpegSegundos;

    @Override
    public CompletableFuture<ResultadoExtracao> processar(Path video, Path diretorioDeTrabalho,
                                                           Path destinoZip, Duration tetoDuracao) {
        return Uni.createFrom().item(() -> processarBloqueante(video, diretorioDeTrabalho, destinoZip, tetoDuracao))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .subscribeAsCompletionStage();
    }

    private ResultadoExtracao processarBloqueante(Path video, Path diretorioDeTrabalho, Path destinoZip,
                                                   Duration tetoDuracao) {
        var duracao = medirDuracaoEValidarStreamDeVideo(video);
        if (duracao.compareTo(tetoDuracao) > 0) {
            throw new FalhaPermanenteDeExtracaoException(MotivoFalha.DURACAO_EXCEDIDA,
                    "duracao " + duracao.getSeconds() + "s acima do teto de " + tetoDuracao.getSeconds() + "s");
        }

        extrairFrames(video, diretorioDeTrabalho);
        var frames = listarFramesOrdenados(diretorioDeTrabalho);
        validarContagemDeFrames(frames.size(), duracao);

        var tamanhoBytes = empacotar(frames, destinoZip);
        return new ResultadoExtracao(frames.size(), tamanhoBytes);
    }

    /** ARQUIVO_INVALIDO se o ffprobe nao roda (ticket 006). */
    private Duration medirDuracaoEValidarStreamDeVideo(Path video) {
        var duracaoBruta = executarCapturandoStdout(timeoutFfprobeSegundos,
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video.toString());

        double segundos;
        try {
            segundos = Double.parseDouble(duracaoBruta.stdout().trim());
        } catch (NumberFormatException | NullPointerException erro) {
            throw new FalhaPermanenteDeExtracaoException(MotivoFalha.ARQUIVO_INVALIDO,
                    "ffprobe nao devolveu duracao valida: " + duracaoBruta.stdout());
        }
        if (duracaoBruta.exitCode() != 0) {
            throw new FalhaPermanenteDeExtracaoException(MotivoFalha.ARQUIVO_INVALIDO,
                    "ffprobe saiu com " + duracaoBruta.exitCode() + ": " + duracaoBruta.stderr());
        }

        var streamDeVideo = executarCapturandoStdout(timeoutFfprobeSegundos,
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0",
                video.toString());
        if (streamDeVideo.stdout() == null || streamDeVideo.stdout().isBlank()) {
            throw new FalhaPermanenteDeExtracaoException(MotivoFalha.SEM_FLUXO_DE_VIDEO,
                    "ffprobe nao encontrou stream de video");
        }

        return Duration.ofMillis((long) (segundos * 1000));
    }

    private void extrairFrames(Path video, Path diretorioDeTrabalho) {
        var padraoSaida = diretorioDeTrabalho.resolve("frame_%04d.png").toString();
        var resultado = executarCapturandoStderr(timeoutFfmpegSegundos,
                "ffmpeg", "-hide_banner", "-nostdin",
                "-loglevel", "level+repeat+error",
                "-xerror", "-nostats", "-progress", "pipe:1",
                "-i", video.toString(),
                "-vf", "fps=1", "-y", padraoSaida);

        if (resultado.exitCode() == 0) {
            return;
        }
        throw classificarFalhaDoFfmpeg(resultado.exitCode(), resultado.stderr());
    }

    /**
     * Exit codes do exit(AVERROR & 0xFF) de {@code fftools/ffmpeg.c}, classificados em
     * docs/pesquisa/ffmpeg-extracao.md. Falha fechada e conservadora: qualquer exit nao
     * reconhecido como permanente e transitorio (ticket 006).
     */
    private RuntimeException classificarFalhaDoFfmpeg(int exitCode, String stderr) {
        LOG.warnf("ffmpeg saiu com exit %d: %s", exitCode, stderr);
        return switch (exitCode) {
            case 183 -> new FalhaPermanenteDeExtracaoException(MotivoFalha.ARQUIVO_INVALIDO,
                    "exit 183 (INVALIDDATA): " + resumo(stderr));
            // Exit 8 colide entre decoder/demuxer/encoder/protocol nao encontrado — todas as
            // causas sao "ffmpeg nao sabe lidar com este conteudo", nao I/O transitorio.
            case 8 -> new FalhaPermanenteDeExtracaoException(MotivoFalha.FORMATO_NAO_SUPORTADO,
                    "exit 8: " + resumo(stderr));
            case 234 -> new FalhaPermanenteDeExtracaoException(MotivoFalha.SEM_FLUXO_DE_VIDEO,
                    "exit 234: " + resumo(stderr));
            default -> new FalhaTransitoriaDeExtracaoException(
                    "ffmpeg saiu com exit " + exitCode + ": " + resumo(stderr));
        };
    }

    private static String resumo(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "(sem stderr)";
        }
        return stderr.length() > 500 ? stderr.substring(stderr.length() - 500) : stderr;
    }

    private List<Path> listarFramesOrdenados(Path diretorioDeTrabalho) {
        try (Stream<Path> arquivos = Files.list(diretorioDeTrabalho)) {
            return arquivos
                    .filter(caminho -> caminho.getFileName().toString().startsWith("frame_"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException erro) {
            throw new FalhaTransitoriaDeExtracaoException("nao foi possivel listar frames", erro);
        }
    }

    /**
     * Defesa em profundidade alem do {@code -xerror} (ticket 006): um MP4 truncado que ainda
     * assim saia com exit 0 produz menos frames do que a duracao promete. Tolerancia de 10%
     * absorve arredondamento do filtro {@code fps=1} nas bordas do video.
     */
    private void validarContagemDeFrames(int quantidadeFrames, Duration duracao) {
        var esperado = Math.max(1, Math.round(duracao.toMillis() / 1000.0));
        if (quantidadeFrames < esperado * 0.9) {
            throw new FalhaPermanenteDeExtracaoException(MotivoFalha.ARQUIVO_INVALIDO,
                    "esperava ~" + esperado + " frames pela duracao, extraiu " + quantidadeFrames);
        }
    }

    /** ZIP STORED: deflate nao comprime PNG (medido, ticket 006). */
    private long empacotar(List<Path> frames, Path destinoZip) {
        try (var saida = new ZipOutputStream(Files.newOutputStream(destinoZip))) {
            for (Path frame : frames) {
                var bytes = Files.readAllBytes(frame);
                var crc = new CRC32();
                crc.update(bytes);

                var entrada = new ZipEntry(frame.getFileName().toString());
                entrada.setMethod(ZipEntry.STORED);
                entrada.setSize(bytes.length);
                entrada.setCompressedSize(bytes.length);
                entrada.setCrc(crc.getValue());

                saida.putNextEntry(entrada);
                saida.write(bytes);
                saida.closeEntry();
            }
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        try {
            return Files.size(destinoZip);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
    }

    private ResultadoDoProcesso executarCapturandoStdout(long timeoutSegundos, String... comando) {
        return executar(timeoutSegundos, true, comando);
    }

    private ResultadoDoProcesso executarCapturandoStderr(long timeoutSegundos, String... comando) {
        return executar(timeoutSegundos, false, comando);
    }

    /**
     * stdout e stderr sempre vao para arquivos, nunca para pipes lidos so depois do {@code
     * waitFor}: um processo cujo stderr enche o buffer do SO antes do pai drenar trava para
     * sempre — o classico deadlock de {@link ProcessBuilder}.
     */
    private ResultadoDoProcesso executar(long timeoutSegundos, boolean capturarStdout, String... comando) {
        Path stdoutArquivo = null;
        Path stderrArquivo = null;
        try {
            stdoutArquivo = Files.createTempFile("extracao-stdout-", ".log");
            stderrArquivo = Files.createTempFile("extracao-stderr-", ".log");

            var processBuilder = new ProcessBuilder(comando)
                    .redirectOutput(stdoutArquivo.toFile())
                    .redirectError(stderrArquivo.toFile());
            var processo = processBuilder.start();

            var terminou = processo.waitFor(timeoutSegundos, TimeUnit.SECONDS);
            if (!terminou) {
                processo.destroyForcibly();
                throw new FalhaTransitoriaDeExtracaoException(
                        comando[0] + " excedeu o timeout de " + timeoutSegundos + "s");
            }

            var stdout = capturarStdout ? Files.readString(stdoutArquivo) : null;
            var stderr = Files.readString(stderrArquivo);
            return new ResultadoDoProcesso(processo.exitValue(), stdout, stderr);
        } catch (IOException erro) {
            throw new FalhaTransitoriaDeExtracaoException("falha ao executar " + comando[0], erro);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new FalhaTransitoriaDeExtracaoException("interrompido executando " + comando[0], erro);
        } finally {
            apagarSilenciosamente(stdoutArquivo);
            apagarSilenciosamente(stderrArquivo);
        }
    }

    private static void apagarSilenciosamente(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException ignorado) {
            // scratch e limpo tambem no boot seguinte (ticket 011); nao vale falhar por isso.
        }
    }

    private record ResultadoDoProcesso(int exitCode, String stdout, String stderr) {
    }
}
