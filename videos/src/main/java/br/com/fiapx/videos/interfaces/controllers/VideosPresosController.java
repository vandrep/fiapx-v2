package br.com.fiapx.videos.interfaces.controllers;

import br.com.fiapx.videos.core.usecases.video.ContarVideosPresosUseCase;

import java.util.concurrent.CompletableFuture;

/**
 * Gatilho burro, como o {@link ReconciliacaoController}: o {@code @Scheduled} em
 * {@code framework} so chama este metodo (ticket 106).
 */
public class VideosPresosController {

    private final ContarVideosPresosUseCase contarVideosPresosUseCase;

    public VideosPresosController(ContarVideosPresosUseCase contarVideosPresosUseCase) {
        this.contarVideosPresosUseCase = contarVideosPresosUseCase;
    }

    public CompletableFuture<ContarVideosPresosUseCase.VideosPresos> contar() {
        return contarVideosPresosUseCase.executar();
    }
}
