package br.com.fiapx.videos.core.usecases.item;

import br.com.fiapx.videos.core.entities.Item;
import br.com.fiapx.videos.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.videos.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.ItemDTO;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuscarItemUseCaseTest {

    @Test
    void deveBuscarItemEApresentarOResultado() {
        ItemGateway gateway = new ItemGateway() {
            @Override
            public CompletableFuture<Long> adicionar(Item item) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<Item> buscarPorId(long id) {
                return CompletableFuture.completedFuture(new Item("Chave de fenda"));
            }
        };
        var dtoApresentado = new AtomicReference<ItemDTO>();
        ItemPresenter presenter = dtoApresentado::set;
        var useCase = new BuscarItemUseCase(gateway, presenter);

        useCase.executar(new BuscarItemUseCase.Command(7L)).join();

        assertEquals(new ItemDTO(7L, "Chave de fenda"), dtoApresentado.get());
    }
}
