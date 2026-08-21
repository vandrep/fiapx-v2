package br.com.fiapx.videos.framework.db;

import br.com.fiapx.videos.core.entities.Item;
import br.com.fiapx.videos.core.exceptions.ItemNaoEncontradoException;
import br.com.fiapx.videos.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.videos.framework.db.entities.ItemEntity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class ItemDataSourceAdapter implements ItemGateway {

    @Override
    public CompletableFuture<Long> adicionar(Item item) {
        return toEntity(item).persistir()
                .map(ItemEntity::id)
                .subscribeAsCompletionStage();
    }

    @Override
    public CompletableFuture<Item> buscarPorId(long id) {
        return ItemEntity.buscaPorId(id)
                .onItem().ifNull().failWith(() -> new ItemNaoEncontradoException(id))
                .map(ItemDataSourceAdapter::toDomain)
                .subscribeAsCompletionStage();
    }

    private static Item toDomain(ItemEntity itemEntity) {
        return new Item(itemEntity.nome);
    }

    private static ItemEntity toEntity(Item domain) {
        var itemEntity = new ItemEntity();
        itemEntity.nome = domain.nome();
        return itemEntity;
    }
}
