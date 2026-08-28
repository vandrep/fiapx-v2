package br.com.fiapx.videos.interfaces.controllers;

import br.com.fiapx.videos.core.usecases.video.ReconciliarPublicacoesPendentesUseCase;

import java.util.concurrent.CompletableFuture;

/**
 * Gatilho burro: o {@code @Scheduled} em {@code framework} so chama este metodo, sem
 * regra propria (ADR 0003).
 */
public class ReconciliacaoController {

    private final ReconciliarPublicacoesPendentesUseCase reconciliarPublicacoesPendentesUseCase;

    public ReconciliacaoController(ReconciliarPublicacoesPendentesUseCase reconciliarPublicacoesPendentesUseCase) {
        this.reconciliarPublicacoesPendentesUseCase = reconciliarPublicacoesPendentesUseCase;
    }

    public CompletableFuture<ReconciliarPublicacoesPendentesUseCase.Republicacoes> reconciliar() {
        return reconciliarPublicacoesPendentesUseCase.executar();
    }
}
