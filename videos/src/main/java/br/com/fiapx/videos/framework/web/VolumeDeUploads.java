package br.com.fiapx.videos.framework.web;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * O volume onde o Vert.x grava o corpo do envio antes de o {@code Resource} rodar — no Compose, o
 * volume nomeado {@code fiapx-uploads} (ticket 011). Bean proprio para que o teste troque o espaco
 * livre sem encher disco nenhum (ticket 108).
 */
@ApplicationScoped
public class VolumeDeUploads {

    @ConfigProperty(name = "quarkus.http.body.uploads-directory")
    Path diretorio;

    public long espacoLivre() {
        return doSistemaDeArquivos(FileStore::getUsableSpace);
    }

    /**
     * No Compose o volume nomeado nao tem tamanho proprio: e o disco do host, e o teto derivado dele
     * depende da maquina.
     */
    public long tamanho() {
        return doSistemaDeArquivos(FileStore::getTotalSpace);
    }

    /** O diretorio pode ainda nao existir; o sistema de arquivos e o do ancestral mais proximo que existe. */
    private long doSistemaDeArquivos(Leitura leitura) {
        var caminho = diretorio.toAbsolutePath();
        while (!Files.exists(caminho) && caminho.getParent() != null) {
            caminho = caminho.getParent();
        }
        try {
            return leitura.de(Files.getFileStore(caminho));
        } catch (IOException falha) {
            throw new UncheckedIOException(falha);
        }
    }

    @FunctionalInterface
    private interface Leitura {
        long de(FileStore sistemaDeArquivos) throws IOException;
    }
}
