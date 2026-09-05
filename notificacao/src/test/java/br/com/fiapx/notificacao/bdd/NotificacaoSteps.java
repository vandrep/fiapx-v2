package br.com.fiapx.notificacao.bdd;

import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O `notificacao` nao tem borda HTTP, mas tem borda: e o RabbitMQ. Estes steps entram por ela
 * — publicam {@code VideoFalhou} no exchange {@code fiapx.eventos} pela mesma routing key do
 * `videos` — e observam a unica saida do servico, o e-mail. Nunca tocam controller, use case
 * ou gateway. O papel que o RestAssured cumpre nos steps do `videos`, a
 * {@link BordaDeMensageria} cumpre aqui (AGENTS.md § BDD).
 *
 * <p>O e-mail e observado pelo {@link MockMailbox} — o mock automatico do quarkus-mailer fora
 * de %prod, que captura o que o adapter entregou ao mailer. E o fim da linha do servico, nao
 * um atalho por dentro dele: o {@code MailerEmailAdapter} roda inteiro.
 */
public class NotificacaoSteps {

    /** Teto da travessia broker -> consumidor -> SMTP. Folga larga sobre o caminho reativo. */
    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Inject
    BordaDeMensageria borda;

    @Inject
    MockMailbox mailbox;

    private UUID idVideo;
    private String nomeArquivo;
    private String codigoMotivo;
    private String emailDono;

    @Before
    public void limparEstadoEntreCenarios() {
        mailbox.clear();
        idVideo = null;
        nomeArquivo = null;
        codigoMotivo = null;
        emailDono = null;
        borda.aguardarFilaDoWorker(ESPERA);
    }

    @Dado("que o vídeo {string} falhou com o motivo {string} para {string}")
    public void queUmVideoFalhouComOMotivoPara(String nomeDoArquivo, String motivo, String email) {
        idVideo = UUID.randomUUID();
        nomeArquivo = nomeDoArquivo;
        codigoMotivo = motivo;
        emailDono = email;
    }

    @Quando("o videos publica o evento VideoFalhou")
    public void oVideosPublicaOEventoVideoFalhou() {
        borda.publicarVideoFalhou(idVideo, "sub-" + idVideo, emailDono, nomeArquivo, codigoMotivo, Instant.now());
    }

    @Entao("um e-mail é enviado para {string}")
    public void umEmailEEnviadoPara(String destinatario) {
        assertEquals(1, aguardarEmails(destinatario).size(),
                () -> "esperava exatamente um e-mail para " + destinatario);
    }

    @E("o assunto do e-mail menciona {string}")
    public void oAssuntoDoEmailMenciona(String trecho) {
        var mensagem = emailUnico();
        assertTrue(mensagem.getSubject().contains(trecho),
                () -> "assunto \"" + mensagem.getSubject() + "\" nao contem \"" + trecho + "\"");
    }

    @E("o corpo do e-mail contém {string}")
    public void oCorpoDoEmailContem(String trecho) {
        var mensagem = emailUnico();
        assertTrue(mensagem.getText().contains(trecho),
                () -> "corpo \"" + mensagem.getText() + "\" nao contem \"" + trecho + "\"");
    }

    /**
     * O {@code codigoMotivo} e vocabulario de contrato entre servicos: o usuario le a frase em
     * portugues do {@code MotivoFalha}, nunca o codigo (docs/contratos/mensagens.md § Codigos
     * de motivo). Vale tambem para o codigo que este servico nao reconhece — ele vira
     * DESCONHECIDO, e nao um eco do que chegou.
     */
    @E("o e-mail não expõe o código técnico {string}")
    public void oEmailNaoExpoeOCodigoTecnico(String codigo) {
        var mensagem = emailUnico();
        assertFalse(mensagem.getSubject().contains(codigo),
                () -> "o assunto vazou o codigo tecnico: " + mensagem.getSubject());
        assertFalse(mensagem.getText().contains(codigo),
                () -> "o corpo vazou o codigo tecnico: " + mensagem.getText());
    }

    private Mail emailUnico() {
        return aguardarEmails(emailDono).get(0);
    }

    private List<Mail> aguardarEmails(String destinatario) {
        long prazo = System.currentTimeMillis() + ESPERA.toMillis();
        while (true) {
            var enviados = mailbox.getMailsSentTo(destinatario);
            if (!enviados.isEmpty()) {
                return enviados;
            }
            if (System.currentTimeMillis() >= prazo) {
                throw new AssertionError("nenhum e-mail chegou a " + destinatario + " em " + ESPERA);
            }
            BordaDeMensageria.dormir(200);
        }
    }
}
