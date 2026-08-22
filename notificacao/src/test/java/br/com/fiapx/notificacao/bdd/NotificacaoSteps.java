package br.com.fiapx.notificacao.bdd;

import br.com.fiapx.notificacao.interfaces.controllers.NotificacaoController;
import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import io.quarkus.mailer.MockMailbox;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `notificacao` nao tem borda HTTP: o {@link NotificacaoController} cumpre o mesmo papel de
 * fronteira que um {@code Resource} cumpre num servico com borda (docs/contratos/
 * mensagens.md § Camadas). Os cenarios chamam o controller diretamente e verificam o e-mail
 * "enviado" via {@link MockMailbox} — o mock automatico do quarkus-mailer fora de %prod,
 * que dispensa MailHog de verdade em teste.
 *
 * <p>Fora de escopo aqui: o transporte RabbitMQ em si (fila, DLQ, ack/nack), que
 * {@code ArchitectureConstraintsTest} e a topologia verificada manualmente contra o
 * management API cobrem (mesmo precedente do ticket 015).
 */
public class NotificacaoSteps {

    @Inject
    NotificacaoController notificacaoController;

    @Inject
    MockMailbox mailbox;

    private UUID idVideo;
    private String nomeArquivo;
    private String codigoMotivo;
    private String emailDono;
    private Exception excecaoCapturada;

    @Before
    public void limparEstadoEntreCenarios() {
        mailbox.clear();
        idVideo = null;
        nomeArquivo = null;
        codigoMotivo = null;
        emailDono = null;
        excecaoCapturada = null;
    }

    @Dado("que um vídeo {string} falhou com o motivo {string} para {string}")
    public void queUmVideoFalhouComOMotivoPara(String nomeDoArquivo, String motivo, String email) {
        idVideo = UUID.randomUUID();
        nomeArquivo = nomeDoArquivo;
        codigoMotivo = motivo;
        emailDono = email;
    }

    @Quando("o notificacao processa esse evento")
    public void oNotificacaoProcessaEsseEvento() {
        try {
            notificacaoController.notificarFalha(idVideo, emailDono, nomeArquivo, codigoMotivo, Instant.now()).get();
        } catch (InterruptedException | ExecutionException erro) {
            excecaoCapturada = erro;
        }
    }

    @Entao("um e-mail é enviado para {string}")
    public void umEmailEEnviadoPara(String destinatario) {
        assertDoesNotThrow(() -> {
            if (excecaoCapturada != null) {
                throw excecaoCapturada;
            }
        });
        assertEquals(1, mailbox.getMailsSentTo(destinatario).size());
    }

    @E("o assunto do e-mail menciona {string}")
    public void oAssuntoDoEmailMenciona(String trecho) {
        var mensagem = mailbox.getMailsSentTo(emailDono).get(0);
        assertTrue(mensagem.getSubject().contains(trecho),
                () -> "assunto \"" + mensagem.getSubject() + "\" nao contem \"" + trecho + "\"");
    }

    @E("o corpo do e-mail contém {string}")
    public void oCorpoDoEmailContem(String trecho) {
        var mensagem = mailbox.getMailsSentTo(emailDono).get(0);
        assertTrue(mensagem.getText().contains(trecho),
                () -> "corpo \"" + mensagem.getText() + "\" nao contem \"" + trecho + "\"");
    }
}
