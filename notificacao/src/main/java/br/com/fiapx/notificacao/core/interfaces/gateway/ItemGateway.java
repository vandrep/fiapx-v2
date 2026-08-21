package br.com.fiapx.notificacao.core.interfaces.gateway;

import br.com.fiapx.notificacao.core.entities.Item;

import java.util.concurrent.CompletableFuture;

public interface ItemGateway {
    CompletableFuture<Long> adicionar(Item item);

    CompletableFuture<Item> buscarPorId(long id);
}
