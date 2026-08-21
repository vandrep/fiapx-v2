package br.com.fiapx.videos.core.usecases.item;

import br.com.fiapx.videos.core.entities.Item;
import br.com.fiapx.videos.core.interfaces.gateway.ItemGateway;

import java.util.concurrent.CompletableFuture;

public class CriarItemUseCase {

    private final ItemGateway itemGateway;

    public CriarItemUseCase(ItemGateway itemGateway) {
        this.itemGateway = itemGateway;
    }

    public CompletableFuture<Long> executar(Command command) {
        var item = new Item(command.nome());
        return itemGateway.adicionar(item);
    }

    public record Command(String nome) {
    }
}
