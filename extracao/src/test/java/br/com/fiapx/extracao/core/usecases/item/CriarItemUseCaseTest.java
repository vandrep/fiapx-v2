package br.com.fiapx.extracao.core.usecases.item;

import br.com.fiapx.extracao.core.entities.Item;
import br.com.fiapx.extracao.core.interfaces.gateway.ItemGateway;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CriarItemUseCaseTest {

    @Test
    void deveCriarItemEDelegarParaOGateway() {
        var itemCriado = new AtomicReference<Item>();
        ItemGateway gateway = new ItemGateway() {
            @Override
            public CompletableFuture<Long> adicionar(Item item) {
                itemCriado.set(item);
                return CompletableFuture.completedFuture(42L);
            }

            @Override
            public CompletableFuture<Item> buscarPorId(long id) {
                throw new UnsupportedOperationException();
            }
        };
        var useCase = new CriarItemUseCase(gateway);

        var id = useCase.executar(new CriarItemUseCase.Command("Chave de fenda")).join();

        assertEquals(42L, id);
        assertEquals("Chave de fenda", itemCriado.get().nome());
    }

    @Test
    void naoDeveCriarItemComNomeInvalido() {
        ItemGateway gateway = new ItemGateway() {
            @Override
            public CompletableFuture<Long> adicionar(Item item) {
                throw new AssertionError("gateway nao deveria ser chamado");
            }

            @Override
            public CompletableFuture<Item> buscarPorId(long id) {
                throw new UnsupportedOperationException();
            }
        };
        var useCase = new CriarItemUseCase(gateway);

        assertThrows(IllegalArgumentException.class,
                () -> useCase.executar(new CriarItemUseCase.Command(" ")));
    }
}
