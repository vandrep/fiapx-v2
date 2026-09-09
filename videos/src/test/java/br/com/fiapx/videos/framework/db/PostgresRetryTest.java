package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A politica do ADR 0001 no acesso ao Postgres: <b>tres chamadas ao banco — a primeira mais
 * duas repeticoes</b>, so sobre indisponibilidade transitoria. A mesma aritmetica que os dois
 * testes de repeticao dos workers cobram do {@code comRepeticao} (ticket 086).
 */
class PostgresRetryTest {

    @Test
    void repeteFalhaTransitoriaComNovaOperacaoAteRecuperar() {
        var chamadas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        var resultado = retry.executar(() -> {
            if (chamadas.incrementAndGet() < 3) {
                return Uni.createFrom().failure(new SQLException("conexao caiu", "08006"));
            }
            return Uni.createFrom().item("recuperado");
        }).subscribeAsCompletionStage().toCompletableFuture().join();

        assertEquals("recuperado", resultado);
        assertEquals(3, chamadas.get(), "duas falhas mais a que sucedeu");
    }

    @Test
    void naoRepeteFalhaPermanente() {
        var chamadas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        assertThrows(Exception.class, () -> retry.executar(() -> {
            chamadas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("violacao", "23505"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(1, chamadas.get());
    }

    @Test
    void naoRepeteErrorMesmoQuandoACausaPareceTransitoria() {
        var chamadas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        assertThrows(CompletionException.class, () -> retry.executar(() -> {
            chamadas.incrementAndGet();
            return Uni.createFrom().failure(
                    new RuntimeException("invocacao falhou",
                            new AssertionError("falha da JVM", new SQLException("conexao caiu", "08006"))));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(1, chamadas.get());
    }

    @Test
    void esgotaDepoisDeTresChamadasAoBanco() {
        var chamadas = new AtomicInteger();
        var retry = new PostgresRetry(Duration.ofMillis(1));

        assertThrows(Exception.class, () -> retry.executar(() -> {
            chamadas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("banco indisponivel", "08001"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(3, chamadas.get(), "a primeira chamada mais as duas repeticoes do ADR 0001");
    }

    @Test
    void reconheceSqlStateDoClienteReativoDoPostgres() {
        var falha = new PgException("conexao caiu", "ERROR", "08006", null);

        assertTrue(PostgresRetry.transitoria(falha));
    }
}
