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
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * O scratch em disco do worker (ticket 011): {@code /var/fiapx/extracao/{idVideo}} sobre o
 * volume nomeado {@code fiapx-extracao-scratch}, orcado em 4 GB. Duas camadas de limpeza —
 * {@link #limpar} por mensagem, e a varredura no boot ({@link #limparOrfaosNoBoot}) para o
 * orfao de crash, que aqui e rotina, nao excecao: o worker morre no meio por desenho. A
 * varredura e segura porque {@code max-outstanding-messages=1} garante que nada mais esta
 * em voo quando o worker inicia.
 */
@ApplicationScoped
public class EspacoDeTrabalhoAdapter implements EspacoDeTrabalhoGateway {

    private static final Logger LOG = Logger.getLogger(EspacoDeTrabalhoAdapter.class);

    @ConfigProperty(name = "fiapx.extracao.scratch-raiz")
    String raiz;

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

    void limparOrfaosNoBoot(@Observes StartupEvent evento) {
        var raizPath = Path.of(raiz);
        try {
            Files.createDirectories(raizPath);
        } catch (IOException erro) {
            throw new UncheckedIOException(erro);
        }
        try (Stream<Path> filhos = Files.list(raizPath)) {
            filhos.forEach(this::apagarRecursivamente);
        } catch (IOException erro) {
            LOG.warnf(erro, "falha ao varrer orfaos em %s no boot", raiz);
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
