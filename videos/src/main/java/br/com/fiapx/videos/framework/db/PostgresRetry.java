package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Executa uma operacao do Hibernate Reactive com retry apenas para indisponibilidade
 * transitoria do Postgres.
 *
 * <p>O {@code deferred} e importante: cada assinatura reabre a sessao ou transacao que o
 * chamador monta dentro do {@code Supplier}. Reassinar uma operacao Panache ja criada depois
 * de uma falha reutilizaria o contexto que o Hibernate marcou como abortado.
 *
 * <p>Os tres retries e o intervalo de dois segundos sao a politica do ADR 0001. O intervalo
 * e configuravel para testes, mas o limite permanece fixo: uma indisponibilidade longa deve
 * voltar ao consumidor HTTP ou ao mecanismo de reentrega da fila.
 */
@ApplicationScoped
public class PostgresRetry {

    private static final int MAX_RETRIES = 3;
    private static final Duration DELAY = Duration.ofSeconds(2);

    private final Duration delay;

    public PostgresRetry() {
        this(DELAY);
    }

    PostgresRetry(Duration delay) {
        this.delay = delay;
    }

    public <T> Uni<T> executar(Supplier<Uni<T>> operacao) {
        return Uni.createFrom().deferred(() -> operacao.get())
                .onFailure(PostgresRetry::transitoria).retry()
                .withBackOff(delay, delay)
                .atMost(MAX_RETRIES);
    }

    static boolean transitoria(Throwable falha) {
        for (Throwable atual = desembrulhar(falha); atual != null; atual = atual.getCause()) {
            if (atual instanceof ConnectException || atual instanceof TimeoutException) {
                return true;
            }
            if (atual instanceof SQLException sql && estadoTransitorio(sql.getSQLState())) {
                return true;
            }
            if (atual instanceof PgException pg && estadoTransitorio(pg.getSqlState())) {
                return true;
            }
            var nome = atual.getClass().getName();
            if (nome.endsWith("JDBCConnectionException")
                    || nome.endsWith("LockAcquisitionException")
                    || nome.endsWith("CannotCreateTransactionException")) {
                return true;
            }
        }
        return false;
    }

    private static Throwable desembrulhar(Throwable falha) {
        var atual = falha;
        while ((atual instanceof java.util.concurrent.CompletionException
                || atual instanceof java.util.concurrent.ExecutionException)
                && atual.getCause() != null) {
            atual = atual.getCause();
        }
        return atual;
    }

    private static boolean estadoTransitorio(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08")
                || sqlState.startsWith("40")
                || sqlState.startsWith("53")
                || sqlState.equals("57P01"));
    }
}
