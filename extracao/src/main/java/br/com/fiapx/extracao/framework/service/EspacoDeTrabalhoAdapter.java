package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.EspacoDeTrabalhoGateway;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * O scratch em disco do worker (ticket 011): {@code /var/fiapx/extracao/{idVideo}-{tentativa}}
 * sobre o volume nomeado {@code fiapx-extracao-scratch}, orcado em 4 GB. Duas camadas de
 * limpeza — {@link #limpar} por mensagem, e a varredura no boot ({@link #limparOrfaosNoBoot})
 * para o orfao de crash, que aqui e rotina, nao excecao: o worker morre no meio por desenho.
 *
 * <p>O sufixo de tentativa e do ticket 041, e nao e enfeite. O nome era so o id do Video, e o
 * volume e o mesmo para todas as replicas: duas tentativas do mesmo Video — comando duplicado
 * tolerado pelo ADR 0003, ou reentrega do {@code failure-strategy=requeue} — cairiam no mesmo
 * diretorio, e preparar uma apagava os frames da outra, enquanto limpar uma apagava os frames
 * da que continuava rodando. Cada tentativa agora tem espaco proprio e limpa <b>so</b> o seu.
 */
@ApplicationScoped
public class EspacoDeTrabalhoAdapter implements EspacoDeTrabalhoGateway {

    private static final Logger LOG = Logger.getLogger(EspacoDeTrabalhoAdapter.class);

    @ConfigProperty(name = "fiapx.extracao.scratch-raiz")
    String raiz;

    @ConfigProperty(name = "fiapx.extracao.idade-minima-do-orfao-minutos", defaultValue = "60")
    long idadeMinimaDoOrfaoMinutos;

    /**
     * {@code createTempDirectory} e nao um {@code UUID.randomUUID()} concatenado a mao: a
     * criacao e atomica, entao duas replicas que preparem a mesma tentativa no mesmo
     * milissegundo nao tem como receber o mesmo caminho. O id do Video fica no prefixo do
     * nome porque quem le o diretorio a mao — no volume, depois de um crash — precisa saber
     * de qual Video e aquele scratch.
     */
    @Override
    public CompletableFuture<Path> prepararNovo(UUID idVideo) {
        return executarBloqueante(() -> {
            try {
                var raizPath = Path.of(raiz);
                Files.createDirectories(raizPath);
                return Files.createTempDirectory(raizPath, idVideo + "-");
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
        });
    }

    /**
     * Recusa o que nao nasceu de {@link #prepararNovo}: sem essa guarda, um caminho errado
     * levaria o {@code Files.walk} recursivo a apagar arquivos de outro dono. Filho direto da
     * raiz e exatamente a forma que este adapter cria.
     *
     * <p>A guarda roda <b>fora</b> do {@link #executarBloqueante}, e de proposito: ali dentro
     * toda falha vira {@link FalhaTransitoriaDeExtracaoException}, e erro de programacao
     * classificado como transitorio volta para a fila e vira mais uma duplicata. Caminho
     * errado nao e coisa que reentregar conserte.
     */
    @Override
    public CompletableFuture<Void> limpar(Path espacoDaTentativa) {
        var caminho = espacoDaTentativa.toAbsolutePath().normalize();
        // Raiz do lado esquerdo: um caminho sem pai devolve null, e `raiz.equals(null)` e
        // false, enquanto `null.equals(raiz)` seria um NPE no lugar da mensagem.
        if (!Path.of(raiz).toAbsolutePath().normalize().equals(caminho.getParent())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "espaco de tentativa fora da raiz do scratch: " + espacoDaTentativa));
        }
        return executarBloqueante(() -> {
            apagarRecursivamente(caminho);
            return null;
        });
    }

    /**
     * O orfao de crash, e <b>so</b> ele: a varredura pula o que foi tocado recentemente.
     *
     * <p>Com o espaco por tentativa (ticket 041), cada filho da raiz e uma tentativa, e nao um
     * Video: a varredura julga cada uma pela sua propria idade, que e a granularidade certa —
     * uma tentativa abandonada por crash some sem levar junto a tentativa viva do mesmo Video.
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
        varrerOrfaos();
    }

    /**
     * A mesma varredura, de tempos em tempos (ticket 041). O boot sozinho nao basta desde que
     * cada tentativa tem diretorio proprio: antes, a reentrega do mesmo Video reciclava o
     * scratch da tentativa morta com o apaga-e-recria — o que era justamente o defeito, porque
     * "tentativa anterior" e "tentativa viva na outra replica" eram indistinguiveis. Sem esse
     * reaproveitamento, quem recupera o abandonado e so a varredura; e a replica que morre e
     * volta acorda com o proprio orfao ainda recente, entao a varredura do boot dela o preserva
     * — medido, um scratch de replica morta no ensaio de conservacao sobreviveu ao restart.
     * Rodando periodicamente, o mesmo gate por idade acaba alcancando-o sem nunca tocar em
     * trabalho vivo.
     */
    @Scheduled(every = "{fiapx.extracao.intervalo-da-varredura}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void limparOrfaosPeriodicamente() {
        varrerOrfaos();
    }

    private void varrerOrfaos() {
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

    private <T> CompletableFuture<T> executarBloqueante(Supplier<T> operacaoBloqueante) {
        return Uni.createFrom().item(operacaoBloqueante)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().transform(erro -> new FalhaTransitoriaDeExtracaoException(
                        "falha no espaco de trabalho: " + erro.getMessage(), erro))
                .subscribeAsCompletionStage();
    }
}
