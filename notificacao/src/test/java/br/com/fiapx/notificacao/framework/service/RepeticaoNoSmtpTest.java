package br.com.fiapx.notificacao.framework.service;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

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
 * <p>Sem container: o bean tem um campo so. Os ~4 s de relogio sao a espera de 2 s do ADR 0001
 * acontecendo de verdade.
 */
class RepeticaoNoSmtpTest {

    /** O servidor que nao volta: falha em toda chamada, nao so nas primeiras. */
    private static final int SEMPRE = Integer.MAX_VALUE;

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
        assertEquals(4, mailer.chamadas(), "a primeira chamada mais as tres repeticoes do ADR 0001");
    }

    private static MailerEmailClient clienteCom(ReactiveMailer mailer) {
        var cliente = new MailerEmailClient();
        cliente.mailer = mailer;
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
