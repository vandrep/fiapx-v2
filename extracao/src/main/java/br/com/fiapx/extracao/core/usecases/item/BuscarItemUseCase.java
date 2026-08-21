package br.com.fiapx.extracao.core.usecases.item;

import br.com.fiapx.extracao.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.extracao.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.extracao.core.interfaces.presenter.dto.ItemDTO;

import java.util.concurrent.CompletableFuture;

public class BuscarItemUseCase {

    private final ItemGateway itemGateway;
    private final ItemPresenter itemPresenter;

    public BuscarItemUseCase(ItemGateway itemGateway, ItemPresenter itemPresenter) {
        this.itemGateway = itemGateway;
        this.itemPresenter = itemPresenter;
    }

    public CompletableFuture<Void> executar(Command command) {
        return itemGateway.buscarPorId(command.id())
                .thenApply(item -> new ItemDTO(command.id(), item.nome()))
                .thenAccept(itemPresenter::present);
    }

    public record Command(long id) {
    }
}
