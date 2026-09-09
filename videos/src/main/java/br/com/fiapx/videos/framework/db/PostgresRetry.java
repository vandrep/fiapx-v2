package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
 * <p><b>Tres chamadas ao banco — a primeira mais duas repeticoes</b> — e o intervalo de dois
 * segundos sao a politica do ADR 0001, na mesma aritmetica que as tres copias de
 * {@code comRepeticao} usam desde o ticket 086. {@code atMost(n)} do Mutiny conta as
 * repeticoes <i>depois</i> da primeira chamada, entao tres chamadas se escrevem
 * {@code atMost(2)}. O intervalo e configuravel para testes, mas o limite permanece fixo: uma
 * indisponibilidade longa deve voltar ao consumidor HTTP ou ao mecanismo de reentrega da fila.
 *
 * <p>O javadoc dizia "tres tentativas totais" e as irmas diziam "tres repeticoes" para numeros
 * diferentes; o 086 deu a palavra <i>tentativa</i> de volta ao {@code CONTEXT.md}, onde ela e
 * uma <i>entrega</i> do trabalho ao `extracao`, e aqui se conta chamada e repeticao.
 */
@ApplicationScoped
public class PostgresRetry {

    /** Duas repeticoes depois da primeira chamada: as tres chamadas ao banco do ADR 0001. */
    private static final int MAX_RETRIES = 2;
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
        var raiz = desembrulhar(falha);
        if (!(raiz instanceof Exception)) {
            return false;
        }
        for (Throwable atual = raiz; atual instanceof Exception; atual = atual.getCause()) {
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
        while ((atual instanceof CompletionException
                || atual instanceof ExecutionException)
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
