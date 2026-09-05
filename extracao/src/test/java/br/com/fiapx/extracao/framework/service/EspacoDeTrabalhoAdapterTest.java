package br.com.fiapx.extracao.framework.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O espaco de trabalho em disco visto pelo lado do adapter: o isolamento por tentativa
 * (ticket 041), a guarda de {@code limpar}, e a varredura de orfaos do boot com o gate por
 * idade que o ticket 027 lhe acrescentou.
 *
 * <p>O volume {@code fiapx-extracao-scratch} e compartilhado por todas as replicas, e sem o
 * gate esta varredura apagava o scratch de quem estivesse extraindo naquele instante — medido
 * no ticket 025 como {@code Error submitting a packet to the muxer} em duas replicas vivas, e
 * um h264 valido entregue ao usuario como ARQUIVO_INVALIDO. O que este teste trava e
 * exatamente a distincao que faltava: <b>velho some, recente fica</b>.
 */
@QuarkusTest
class EspacoDeTrabalhoAdapterTest {

    @Inject
    EspacoDeTrabalhoAdapter adapter;

    // Do config, e nao de adapter.raiz: o campo lido pelo proxy do CDI vem nulo.
    @ConfigProperty(name = "fiapx.extracao.scratch-raiz")
    String raiz;

    @Test
    void aVarreduraApagaOOrfaoVelhoEPreservaOScratchEmUso() throws IOException {
        var recente = criarScratchCom(Instant.now());
        var velho = criarScratchCom(Instant.now().minus(Duration.ofHours(3)));

        adapter.limparOrfaosNoBoot(new StartupEvent());

        assertFalse(Files.exists(velho), "o orfao de crash tinha de ter sido varrido");
        assertTrue(Files.exists(recente),
                "o scratch de uma replica viva nao pode ser apagado pelo boot de outra");
    }

    /**
     * A idade tem de vir do arquivo mais recente em qualquer profundidade: o ffmpeg grava os
     * frames <b>dentro</b> do diretorio, e nem todo sistema de arquivos toca o mtime do pai.
     */
    @Test
    void oFrameRecemGravadoSalvaODiretorioComMtimeAntigo() throws IOException {
        var diretorio = criarScratchCom(Instant.now().minus(Duration.ofHours(3)));
        var frame = diretorio.resolve("frame_0001.png");
        Files.writeString(frame, "conteudo");
        Files.setLastModifiedTime(frame, FileTime.from(Instant.now()));
        Files.setLastModifiedTime(diretorio, FileTime.from(Instant.now().minus(Duration.ofHours(3))));

        adapter.limparOrfaosNoBoot(new StartupEvent());

        assertTrue(Files.exists(frame), "o diretorio estava sendo escrito agora mesmo");
    }

    /**
     * A varredura periodica e a mesma do boot, e precisa continuar sendo: e ela que alcanca o
     * orfao da replica que morreu e voltou, que no boot dela ainda era recente demais para o
     * gate por idade (ticket 041).
     */
    @Test
    void aVarreduraPeriodicaTambemApagaOOrfaoVelhoEPreservaOEmUso() throws IOException {
        var recente = criarScratchCom(Instant.now());
        var velho = criarScratchCom(Instant.now().minus(Duration.ofHours(3)));

        adapter.limparOrfaosPeriodicamente();

        assertFalse(Files.exists(velho), "o orfao de crash tinha de ter sido varrido");
        assertTrue(Files.exists(recente), "trabalho vivo nao pode ser apagado pela varredura periodica");
    }

    /**
     * A colisao do ticket 041: duas replicas recebem o comando duplicado do <b>mesmo</b> Video
     * sobre a raiz compartilhada. Com o scratch nomeado pelo id do Video, preparar a segunda
     * tentativa apagava os frames da primeira, e limpar a primeira apagava os da segunda —
     * cada uma destruindo o trabalho da outra. Duas instancias do adapter, e nao uma: e o
     * arranjo do Compose, onde o volume {@code fiapx-extracao-scratch} e o mesmo para todas.
     */
    @Test
    void duasTentativasDoMesmoVideoNaoSePisam() throws Exception {
        var idVideo = UUID.randomUUID();
        var replicaA = adapterSobre(raiz);
        var replicaB = adapterSobre(raiz);

        var tentativaA = replicaA.prepararNovo(idVideo).get();
        var frameA = Files.writeString(tentativaA.resolve("frame_0001.png"), "replica A");

        var tentativaB = replicaB.prepararNovo(idVideo).get();

        assertNotEquals(tentativaA, tentativaB, "cada tentativa precisa do seu proprio espaco");
        assertTrue(Files.exists(frameA), "preparar a segunda tentativa apagou o frame da primeira");

        var frameB = Files.writeString(tentativaB.resolve("frame_0001.png"), "replica B");

        replicaA.limpar(tentativaA).get();

        assertFalse(Files.exists(tentativaA), "a tentativa que terminou tem de sumir inteira");
        assertTrue(Files.exists(frameB), "limpar a primeira tentativa apagou o frame da segunda");

        replicaB.limpar(tentativaB).get();
        assertFalse(Files.exists(tentativaB));
    }

    /**
     * A limpeza agora recebe um caminho em vez do id do Video, entao ela precisa recusar o que
     * nao nasceu aqui: um caminho de fora da raiz apagaria arquivos alheios com o mesmo
     * {@code Files.walk} recursivo.
     */
    @Test
    void limparRecusaCaminhoForaDaRaiz() throws IOException {
        var deOutroDono = Files.createTempDirectory("fiapx-fora-da-raiz");
        var alheio = Files.writeString(deOutroDono.resolve("arquivo.txt"), "nao e nosso");

        var erro = assertThrows(ExecutionException.class,
                () -> adapterSobre(raiz).limpar(deOutroDono).get());
        // IllegalArgumentException, e nao FalhaTransitoria: caminho errado e defeito de
        // programacao, e classifica-lo como transitorio o devolveria para a fila.
        assertInstanceOf(IllegalArgumentException.class, erro.getCause());
        assertTrue(Files.exists(alheio), "so a raiz do scratch pode ser apagada por aqui");

        Files.delete(alheio);
        Files.delete(deOutroDono);
    }

    /**
     * O CDI injeta o adapter da aplicacao; aqui a montagem e manual de proposito, para o teste
     * ter <b>duas</b> instancias sobre a mesma raiz — o que o Compose tem, e o que a injecao
     * de um bean {@code @ApplicationScoped} nao da.
     */
    private static EspacoDeTrabalhoAdapter adapterSobre(String raiz) {
        var instancia = new EspacoDeTrabalhoAdapter();
        instancia.raiz = raiz;
        instancia.idadeMinimaDoOrfaoMinutos = 60;
        return instancia;
    }

    /**
     * Nome no formato que o adapter cria desde o ticket 041 — {@code {idVideo}-{sufixo}} —, e
     * nao o id pelado de antes: a varredura julga tentativa, e o teste tem de varrer o que a
     * producao de fato deixa no volume.
     */
    private Path criarScratchCom(Instant quando) throws IOException {
        var diretorio = Path.of(raiz, UUID.randomUUID() + "-" + System.nanoTime());
        Files.createDirectories(diretorio);
        Files.setLastModifiedTime(diretorio, FileTime.from(quando));
        return diretorio;
    }
}
