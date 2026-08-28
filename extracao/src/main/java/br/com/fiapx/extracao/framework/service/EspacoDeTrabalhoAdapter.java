package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.EspacoDeTrabalhoGateway;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * O scratch em disco do worker (ticket 011): {@code /var/fiapx/extracao/{idVideo}} sobre o
 * volume nomeado {@code fiapx-extracao-scratch}, orcado em 4 GB. Duas camadas de limpeza —
 * {@link #limpar} por mensagem, e a varredura no boot ({@link #limparOrfaosNoBoot}) para o
 * orfao de crash, que aqui e rotina, nao excecao: o worker morre no meio por desenho.
 */
@ApplicationScoped
public class EspacoDeTrabalhoAdapter implements EspacoDeTrabalhoGateway {

    private static final Logger LOG = Logger.getLogger(EspacoDeTrabalhoAdapter.class);

    @ConfigProperty(name = "fiapx.extracao.scratch-raiz")
    String raiz;

    @ConfigProperty(name = "fiapx.extracao.idade-minima-do-orfao-minutos", defaultValue = "60")
    long idadeMinimaDoOrfaoMinutos;

    @Override
    public CompletableFuture<Path> prepararNovo(UUID idVideo) {
        return executarBloqueante(() -> {
            var diretorio = diretorioDoVideo(idVideo);
            apagarRecursivamente(diretorio);
            try {
                Files.createDirectories(diretorio);
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
            return diretorio;
        });
    }

    @Override
    public CompletableFuture<Void> limpar(UUID idVideo) {
        return executarBloqueante(() -> {
            apagarRecursivamente(diretorioDoVideo(idVideo));
            return null;
        });
    }

    /**
     * O orfao de crash, e <b>so</b> ele: a varredura pula o que foi tocado recentemente.
     *
     * <p>O gate por idade nao e zelo, e correcao. O volume nomeado {@code fiapx-extracao-scratch}
     * e compartilhado por todas as replicas, entao "tudo que esta na raiz" inclui o scratch de
     * quem esta extraindo agora — esta varredura apagava por baixo do ffmpeg de replicas vivas
     * toda vez que uma nova subia. Medido no ticket 025: duas replicas com
     * {@code Error submitting a packet to the muxer: No such file or directory} no instante do
     * boot de uma terceira, e um h264 valido entregue ao usuario como ARQUIVO_INVALIDO.
     *
     * <p>O limiar so precisa ser maior que a Extracao mais longa possivel — o teto de duracao
     * do ticket 011 e 20 minutos, e o timeout do ffmpeg e 300 s —, e o ffmpeg toca o diretorio
     * a cada frame gravado. Uma hora e folga larga sobre os dois.
     */
    void limparOrfaosNoBoot(@Observes StartupEvent evento) {
        var raizPath = Path.of(raiz);
        try {
            Files.createDirectories(raizPath);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        var limite = Instant.now().minus(Duration.ofMinutes(idadeMinimaDoOrfaoMinutos));
        try (Stream<Path> filhos = Files.list(raizPath)) {
            filhos.filter(filho -> ociosoDesdeAntesDe(filho, limite))
                    .forEach(this::apagarRecursivamente);
        } catch (IOException erro) {
            LOG.warnf(erro, "falha ao varrer orfaos em %s no boot", raiz);
        }
    }

    /**
     * A idade e a do arquivo mais recente <b>em qualquer profundidade</b>, nao a do diretorio
     * de topo: o ffmpeg grava {@code frame_NNNN.png} dentro dele sem tocar o mtime do pai em
     * todo sistema de arquivos. Na duvida, conservador — nao conseguir ler a idade conta como
     * "esta em uso" e o diretorio sobrevive ate o proximo boot.
     */
    private boolean ociosoDesdeAntesDe(Path diretorio, Instant limite) {
        try (Stream<Path> caminhos = Files.walk(diretorio)) {
            var maisRecente = caminhos
                    .map(EspacoDeTrabalhoAdapter::modificadoEm)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
            if (maisRecente.isBefore(limite)) {
                return true;
            }
            LOG.infof("scratch %s tocado em %s: em uso por outra replica, preservado", diretorio, maisRecente);
            return false;
        } catch (IOException erro) {
            LOG.warnf(erro, "nao consegui datar %s; preservado", diretorio);
            return false;
        }
    }

    private static Instant modificadoEm(Path caminho) {
        try {
            return Files.getLastModifiedTime(caminho).toInstant();
        } catch (IOException erro) {
            return Instant.MAX;
        }
    }

    private Path diretorioDoVideo(UUID idVideo) {
        return Path.of(raiz, idVideo.toString());
    }

    private void apagarRecursivamente(Path diretorio) {
        if (!Files.exists(diretorio)) {
            return;
        }
        try (Stream<Path> caminhos = Files.walk(diretorio)) {
            caminhos.sorted(Comparator.reverseOrder()).forEach(caminho -> {
                try {
                    Files.delete(caminho);
                } catch (IOException ignorado) {
                    // varredura do proximo boot cobre o que sobrar (ticket 011).
                }
            });
        } catch (IOException erro) {
            LOG.warnf(erro, "falha ao apagar %s", diretorio);
        }
    }

    private <T> CompletableFuture<T> executarBloqueante(java.util.function.Supplier<T> operacaoBloqueante) {
        return Uni.createFrom().item(operacaoBloqueante)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(erro -> new FalhaTransitoriaDeExtracaoException(
                        "falha no espaco de trabalho: " + erro.getMessage(), erro))
                .subscribeAsCompletionStage();
    }
}
