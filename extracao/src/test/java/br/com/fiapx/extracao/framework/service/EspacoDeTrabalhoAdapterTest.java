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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A varredura de orfaos do boot, e o gate por idade que o ticket 027 lhe acrescentou.
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

    private Path criarScratchCom(Instant quando) throws IOException {
        var diretorio = Path.of(raiz, UUID.randomUUID().toString());
        Files.createDirectories(diretorio);
        Files.setLastModifiedTime(diretorio, FileTime.from(quando));
        return diretorio;
    }
}
