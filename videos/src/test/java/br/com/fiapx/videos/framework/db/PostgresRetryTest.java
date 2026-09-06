package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRetryTest {

    @Test
    void repeteFalhaTransitoriaComNovaOperacaoAteRecuperar() {
        var tentativas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        var resultado = retry.executar(() -> {
            if (tentativas.incrementAndGet() < 3) {
                return Uni.createFrom().failure(new SQLException("conexao caiu", "08006"));
            }
            return Uni.createFrom().item("recuperado");
        }).subscribeAsCompletionStage().toCompletableFuture().join();

        assertEquals("recuperado", resultado);
        assertEquals(3, tentativas.get());
    }

    @Test
    void naoRepeteFalhaPermanente() {
        var tentativas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        assertThrows(Exception.class, () -> retry.executar(() -> {
            tentativas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("violacao", "23505"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(1, tentativas.get());
    }

    @Test
    void esgotaDepoisDeTresRetentativas() {
        var tentativas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        assertThrows(Exception.class, () -> retry.executar(() -> {
            tentativas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("banco indisponivel", "08001"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(4, tentativas.get());
    }

    @Test
    void reconheceSqlStateDoClienteReativoDoPostgres() {
        var falha = new PgException("conexao caiu", "ERROR", "08006", null);

        assertTrue(PostgresRetry.transitoria(falha));
    }
}
