package br.com.fiapx.notificacao.framework.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que o ADR 0001 promete no envio de SMTP: um blip do servidor de e-mail e absorvido por
 * {@link MailerEmailClient}, e um servidor que nao volta acaba falhando em vez de insistir
 * para sempre — e ai a mensagem circula pela DLQ em vez de sumir.
 *
 * <p>Existe pelo mesmo motivo do {@code RepeticaoNoMinioTest} do `extracao`: o ticket 061
 * tirou o {@code @Retry} destes tres serviços, e o que precisa continuar travado e a
 * <b>politica</b>, nao a anotacao que a implementava. Sem este teste, o `notificacao` seria o
 * unico dos tres com a politica reescrita e nenhuma trava sobre ela.
 *
 * <p><b>Sao tres chamadas ao recurso — a primeira mais duas repeticoes</b>, que e a aritmetica
 * unica do ADR 0001 desde o ticket 086. O cenario do blip falha as duas primeiras de proposito:
 * assim ele sucede na ultima chamada que a politica permite, e reprova tanto se a repeticao
 * sumir quanto se sobrar uma.
 *
 * <p>Sem container: o bean e montado a mao, o que dispensa o boot do Quarkus e deixa a espera
 * entre repeticoes ser atribuida direto no campo.
 *
 * <p><b>A espera aqui e 1 ms, e nao os 2 s de producao</b> (ticket 085). O que esta sob
 * julgamento e a repeticao <i>acontecer</i> e a ultima falha chegar ao chamador, nao quanto ela
 * espera; com os 2 s a classe levava <b>10,18 s</b>, e com 1 ms leva <b>0,15 s</b>. Que os
 * 2 s do ADR 0001 sejam 2 s fica guardado pelo default do {@code @ConfigProperty}, e nao por
 * cenario. O piso e 1 ms e nao zero: o Mutiny recusa backoff zero com
 * {@code IllegalArgumentException} na subscricao.
 */
class RepeticaoNoSmtpTest {

    /** O servidor que nao volta: falha em toda chamada, nao so nas primeiras. */
    private static final int SEMPRE = Integer.MAX_VALUE;

    /** O piso que o Mutiny aceita: backoff zero e recusado com {@code IllegalArgumentException}. */
    private static final Duration ESPERA_DO_TESTE = Duration.ofMillis(1);

    @Test
    void blipDoServidorDeEmailEAbsorvidoPelaRepeticao() throws Exception {
        var mailer = new MailerQueFalhaAsPrimeiras(2);

        clienteCom(mailer).enviar(Mail.withText("demo@fiapx.local", "assunto", "corpo"))
                .toCompletableFuture().get();

        assertEquals(3, mailer.chamadas(), "duas falhas mais a que sucedeu");
    }

    @Test
    void servidorPersistentementeForaFalhaEmVezDeInsistirParaSempre() {
        var mailer = new MailerQueFalhaAsPrimeiras(SEMPRE);

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(mailer).enviar(Mail.withText("demo@fiapx.local", "assunto", "corpo"))
                        .toCompletableFuture().get());

        assertTrue(falha.getCause() instanceof IllegalStateException,
                "a ultima falha do SMTP tinha de chegar ao chamador, e chegou " + falha.getCause());
        assertEquals(3, mailer.chamadas(), "a primeira chamada mais as duas repeticoes do ADR 0001");
    }

    private static MailerEmailClient clienteCom(ReactiveMailer mailer) {
        var cliente = new MailerEmailClient();
        cliente.mailer = mailer;
        cliente.esperaEntreRepeticoes = ESPERA_DO_TESTE;
        return cliente;
    }

    private static class MailerQueFalhaAsPrimeiras implements ReactiveMailer {

        private final int falhasIniciais;
        private final AtomicInteger chamadas = new AtomicInteger();

        private MailerQueFalhaAsPrimeiras(int falhasIniciais) {
            this.falhasIniciais = falhasIniciais;
        }

        private int chamadas() {
            return chamadas.get();
        }

        @Override
        public Uni<Void> send(Mail... mails) {
            return Uni.createFrom().deferred(() -> chamadas.incrementAndGet() <= falhasIniciais
                    ? Uni.createFrom().failure(new IllegalStateException("SMTP indisponivel nesta chamada"))
                    : Uni.createFrom().voidItem());
        }
    }
}
