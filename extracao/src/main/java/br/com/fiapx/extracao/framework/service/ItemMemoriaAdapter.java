package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.entities.Item;
import br.com.fiapx.extracao.core.exceptions.ItemNaoEncontradoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ItemGateway;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Placeholder do esqueleto: este servico nao tem banco (so `videos` tem). Sera trocado
 * pelo adapter real de RabbitMQ/S3 quando o modulo ganhar dominio de verdade.
 */
@ApplicationScoped
public class ItemMemoriaAdapter implements ItemGateway {

    private final Map<Long, Item> itens = new ConcurrentHashMap<>();
    private final AtomicLong proximoId = new AtomicLong();

    @Override
    public CompletableFuture<Long> adicionar(Item item) {
        var id = proximoId.incrementAndGet();
        itens.put(id, item);
        return CompletableFuture.completedFuture(id);
    }

    @Override
    public CompletableFuture<Item> buscarPorId(long id) {
        var item = itens.get(id);
        return item == null
                ? CompletableFuture.failedFuture(new ItemNaoEncontradoException(id))
                : CompletableFuture.completedFuture(item);
    }
}
