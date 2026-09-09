package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A politica do ADR 0001 no acesso ao Postgres: <b>tres chamadas ao banco — a primeira mais
 * duas repeticoes</b>, so sobre indisponibilidade transitoria. A mesma aritmetica que os dois
 * testes de repeticao dos workers cobram do {@code comRepeticao} (ticket 086).
 *
 * <p>Sem container de proposito, como no {@code RepeticaoNoMinioTest}: montar o bean a mao
 * dispensa o boot do Quarkus e deixa a espera entre repeticoes ser atribuida direto no campo
 * (ticket 087).
 */
class RepeticaoNoPostgresTest {

    /**
     * O piso que o Mutiny aceita: backoff zero e recusado com {@code IllegalArgumentException}.
     * O que estes cenarios julgam e a repeticao <i>acontecer</i> e parar na hora certa, nao
     * quanto ela espera — os 2 s do ADR 0001 ficam guardados pelo default do
     * {@code @ConfigProperty}, como nas tres copias de {@code comRepeticao} (ticket 085).
     */
    private static final Duration ESPERA_DO_TESTE = Duration.ofMillis(1);

    @Test
    void repeteFalhaTransitoriaComNovaOperacaoAteRecuperar() {
        var chamadas = new AtomicInteger();
        var banco = repeticao();

        var resultado = banco.executar(() -> {
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
        var banco = repeticao();

        assertThrows(Exception.class, () -> banco.executar(() -> {
            chamadas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("violacao", "23505"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(1, chamadas.get());
    }

    @Test
    void naoRepeteErrorMesmoQuandoACausaPareceTransitoria() {
        var chamadas = new AtomicInteger();
        var banco = repeticao();

        assertThrows(CompletionException.class, () -> banco.executar(() -> {
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
        var banco = repeticao();

        assertThrows(Exception.class, () -> banco.executar(() -> {
            chamadas.incrementAndGet();
            return Uni.createFrom().failure(new SQLException("banco indisponivel", "08001"));
        }).subscribeAsCompletionStage().toCompletableFuture().join());

        assertEquals(3, chamadas.get(), "a primeira chamada mais as duas repeticoes do ADR 0001");
    }

    @Test
    void reconheceSqlStateDoClienteReativoDoPostgres() {
        var falha = new PgException("conexao caiu", "ERROR", "08006", null);

        assertTrue(RepeticaoNoPostgres.transitoria(falha));
    }

    @Test
    void reconheceALockAcquisitionExceptionDoHibernateSemDependerDoSqlState() {
        var falha = new LockAcquisitionException("lock nao adquirido", new SQLException("lock"));

        assertTrue(RepeticaoNoPostgres.transitoria(falha));
    }

    @Test
    void naoConfundeUmaClasseHomonimaDeOutroPacoteComADoHibernate() {
        var falha = new OutroPacote.LockAcquisitionException("homonima, e sem parentesco");

        assertFalse(RepeticaoNoPostgres.transitoria(falha),
                "classificar por nome simples repetiria qualquer classe com o nome certo");
    }

    private static RepeticaoNoPostgres repeticao() {
        var repeticao = new RepeticaoNoPostgres();
        repeticao.esperaEntreRepeticoes = ESPERA_DO_TESTE;
        return repeticao;
    }

    /**
     * O papel de "outro pacote": uma classe cujo <b>nome simples</b> coincide com o da
     * {@code org.hibernate.exception.LockAcquisitionException} e que nao tem parentesco nenhum
     * com ela. E o que a classificacao por {@code endsWith} sobre o nome qualificado nao
     * consegue distinguir.
     */
    private static final class OutroPacote {

        private OutroPacote() {
        }

        private static final class LockAcquisitionException extends RuntimeException {

            private LockAcquisitionException(String mensagem) {
                super(mensagem);
            }
        }
    }
}
