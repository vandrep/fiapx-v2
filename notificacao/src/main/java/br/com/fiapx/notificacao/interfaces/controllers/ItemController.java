package br.com.fiapx.notificacao.interfaces.controllers;

import br.com.fiapx.notificacao.core.usecases.item.BuscarItemUseCase;
import br.com.fiapx.notificacao.core.usecases.item.CriarItemUseCase;

import java.util.concurrent.CompletableFuture;

public class ItemController {

    private final CriarItemUseCase criarItemUseCase;
    private final BuscarItemUseCase buscarItemUseCase;

    public ItemController(CriarItemUseCase criarItemUseCase, BuscarItemUseCase buscarItemUseCase) {
        this.criarItemUseCase = criarItemUseCase;
        this.buscarItemUseCase = buscarItemUseCase;
    }

    public CompletableFuture<Long> criar(ItemRequest request) {
        var command = new CriarItemUseCase.Command(request.nome());
        return criarItemUseCase.executar(command);
    }

    public CompletableFuture<Void> buscar(Long id) {
        var command = new BuscarItemUseCase.Command(id);
        return buscarItemUseCase.executar(command);
    }

    public record ItemRequest(String nome) {
    }
}
