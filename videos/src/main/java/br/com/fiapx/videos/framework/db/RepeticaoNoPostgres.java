package br.com.fiapx.videos.framework.db;

import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.LockAcquisitionException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Executa uma operacao do Hibernate Reactive com repeticao apenas para indisponibilidade
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
 * {@code atMost(2)}. A contagem e fixa e so a espera e configuravel: o numero de chamadas e a
 * politica, e a espera e o preco dela. Uma indisponibilidade longa deve voltar ao consumidor
 * HTTP ou ao mecanismo de reentrega da fila.
 *
 * <p><b>Repeticao, e nao "tentativa".</b> No {@code CONTEXT.md} tentativa e uma <i>entrega</i>
 * do trabalho ao `extracao`; estas aqui sao chamadas ao banco dentro de <b>uma</b> tentativa
 * (ticket 086).
 *
 * <p>Esta e a segunda implementacao da mesma forma reativa dentro do `videos` — a outra e o
 * {@code comRepeticao} do {@code ArquivoMinioClient}. O ticket 087 alinhou as duas em tudo o
 * que nao tinha motivo para divergir: vocabulario, costura de configuracao da espera, jitter e
 * a aritmetica. <b>O que continua diferente, e por que</b>, esta no `AGENTS.md`
 * § *As copias deliberadas entre servicos*: o filtro de falha. O MinIO repete qualquer
 * {@code Exception}; aqui so a indisponibilidade transitoria e repetida, porque uma violacao
 * de constraint ou um erro de SQL repetido tres vezes da tres vezes o mesmo erro e ainda
 * segura a borda por 4 s.
 */
@ApplicationScoped
public class RepeticaoNoPostgres {

    /** Duas repeticoes depois da primeira chamada: as tres chamadas ao banco do ADR 0001. */
    private static final int MAXIMO_DE_REPETICOES = 2;

    /**
     * Fracao da espera, e nao valor absoluto: o Mutiny pede fracao. Sao os mesmos 10% das tres
     * copias de {@code comRepeticao}, e aqui eles pesam mais do que la: o Postgres esta atras
     * de um pool de conexoes compartilhado, entao repeticoes sem jitter voltam todas juntas
     * sobre o mesmo pool que acabou de ceder (ticket 087).
     */
    private static final double JITTER = 0.1;

    /**
     * Os 2 s do ADR 0001, na mesma costura das tres copias de {@code comRepeticao} — e nao no
     * construtor package-private que so o teste chamava (ticket 087). E configuracao <b>deste
     * bean</b>, no namespace {@code fiapx.} do projeto, e nao chave de tolerancia a falhas por
     * interceptor, que a setima regra do teste arquitetural proibe desde o ticket 064.
     *
     * <p>O default vive aqui, e nao no {@code .properties}, para que a espera de producao
     * sobreviva a um arquivo de configuracao incompleto (ticket 080). Nenhum {@code %test.} o
     * baixa: quem exercita a repeticao e o {@code RepeticaoNoPostgresTest}, que monta o bean a
     * mao e atribui o campo direto — o mesmo motivo pelo qual o `extracao` e o `notificacao`
     * tambem nao tem a chave no {@code .properties} (ticket 085).
     */
    @ConfigProperty(name = "fiapx.banco.espera-entre-repeticoes", defaultValue = "2s")
    Duration esperaEntreRepeticoes;

    public <T> Uni<T> executar(Supplier<Uni<T>> operacao) {
        return Uni.createFrom().deferred(() -> operacao.get())
                .onFailure(RepeticaoNoPostgres::transitoria).retry()
                .withBackOff(esperaEntreRepeticoes, esperaEntreRepeticoes)
                .withJitter(JITTER)
                .atMost(MAXIMO_DE_REPETICOES);
    }

    /**
     * Classifica por tipo, e nao pelo nome da classe. Ate o ticket 087 os tres tipos do
     * Hibernate eram reconhecidos por {@code getName().endsWith(...)}, o que casava qualquer
     * classe de qualquer pacote com aquele nome simples e sumia em silencio a um rename dentro
     * do Hibernate — a repeticao simplesmente pararia de acontecer. As duas excecoes de fato
     * usadas vem do {@code hibernate-core}, que ja esta no classpath de compilacao pelo
     * {@code quarkus-hibernate-reactive-panache}; a terceira,
     * {@code CannotCreateTransactionException}, e do Spring, nunca esteve neste classpath e
     * saiu junto.
     */
    static boolean transitoria(Throwable falha) {
        var raiz = desembrulhar(falha);
        if (!(raiz instanceof Exception)) {
            return false;
        }
        for (Throwable atual = raiz; atual instanceof Exception; atual = atual.getCause()) {
            if (atual instanceof ConnectException || atual instanceof TimeoutException) {
                return true;
            }
            if (atual instanceof JDBCConnectionException || atual instanceof LockAcquisitionException) {
                return true;
            }
            if (atual instanceof SQLException sql && estadoTransitorio(sql.getSQLState())) {
                return true;
            }
            if (atual instanceof PgException pg && estadoTransitorio(pg.getSqlState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Desembrulha <b>so</b> os envelopes de {@code CompletionStage} para chegar a falha que o
     * banco produziu. Nao e o mesmo percurso da cadeia de causas que o `extracao` faz em
     * {@code ProcessarExtracaoUseCase} e em {@code ExtrairVideoConsumer}, e o ticket 087
     * decidiu nao unifica-los: ver o `AGENTS.md` § *As copias deliberadas entre servicos*.
     */
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
