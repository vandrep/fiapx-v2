package br.com.fiapx.videos.core.interfaces.gateway;

import br.com.fiapx.videos.core.entities.Item;

import java.util.concurrent.CompletableFuture;

public interface ItemGateway {
    CompletableFuture<Long> adicionar(Item item);

    CompletableFuture<Item> buscarPorId(long id);
}
