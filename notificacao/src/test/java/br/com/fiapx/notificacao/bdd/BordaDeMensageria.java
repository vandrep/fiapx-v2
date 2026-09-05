package br.com.fiapx.notificacao.bdd;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A borda do `notificacao` vista de fora, para os cenarios BDD: o broker de verdade que os Dev
 * Services sobem. O que entra e o evento {@code VideoFalhou} publicado pelo `videos` em
 * {@code fiapx.eventos}; o que sai e um e-mail, e nao uma mensagem — por isso esta classe so
 * tem lado de entrada, ao contrario da homonima do `extracao`. Nada aqui conhece controller,
 * use case ou gateway: fala AMQP puro, como os steps do `videos` falam HTTP pelo RestAssured
 * (AGENTS.md § BDD).
 *
 * <p>O exchange e declarado com os mesmos argumentos que o conector SmallRye usa
 * ({@code topic}, duravel, sem auto-delete), entao a redeclaracao e idempotente.
 */
@ApplicationScoped
public class BordaDeMensageria {

    private static final String EXCHANGE_DE_EVENTOS = "fiapx.eventos";
    private static final String ROUTING_KEY_DO_EVENTO = "video.falhou";
    private static final String FILA_DO_WORKER = "notificacao.video-falhou";

    private static final AMQP.BasicProperties JSON = new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .build();

    @ConfigProperty(name = "rabbitmq-host")
    String host;

    @ConfigProperty(name = "rabbitmq-port")
    int porta;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String usuario;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String senha;

    private Connection conexao;
    private Channel canal;

    @PostConstruct
    void abrir() {
        var fabrica = new ConnectionFactory();
        fabrica.setHost(host);
        fabrica.setPort(porta);
        fabrica.setUsername(usuario);
        fabrica.setPassword(senha);
        try {
            conexao = fabrica.newConnection("bdd-notificacao");
            canal = conexao.createChannel();
            canal.exchangeDeclare(EXCHANGE_DE_EVENTOS, "topic", true);
        } catch (Exception erro) {
            throw new IllegalStateException("nao consegui abrir a borda de mensageria do teste", erro);
        }
    }

    @PreDestroy
    void fechar() {
        try {
            if (conexao != null) {
                conexao.close();
            }
        } catch (Exception ignorado) {
            // Fim de suite: uma conexao que ja caiu nao tem o que reportar.
        }
    }

    /**
     * Espera a fila do worker existir antes de publicar. Um exchange {@code topic} sem binding
     * descarta a mensagem em silencio, e o cenario reprovaria por corrida de boot em vez de
     * por defeito. O passive declare vai num canal descartavel de proposito: quando a fila
     * ainda nao existe, o broker fecha o canal em que a pergunta foi feita.
     */
    public void aguardarFilaDoWorker(Duration limite) {
        long prazo = System.currentTimeMillis() + limite.toMillis();
        while (System.currentTimeMillis() < prazo) {
            if (filaExiste()) {
                return;
            }
            dormir(200);
        }
        throw new IllegalStateException(FILA_DO_WORKER + " nao apareceu em " + limite
                + ": o consumidor do notificacao nao subiu");
    }

    private boolean filaExiste() {
        Channel efemero = null;
        try {
            efemero = conexao.createChannel();
            efemero.queueDeclarePassive(FILA_DO_WORKER);
            return true;
        } catch (Exception naoExiste) {
            return false;
        } finally {
            fecharSilenciosamente(efemero);
        }
    }

    /** Publica {@code VideoFalhou} pela mesma routing key que o `videos` usa em producao. */
    public void publicarVideoFalhou(UUID idVideo, String donoSub, String emailDono,
                                    String nomeArquivoOriginal, String codigoMotivo, Instant ocorridoEm) {
        var evento = new JsonObject()
                .put("idVideo", idVideo.toString())
                .put("donoSub", donoSub)
                .put("emailDono", emailDono)
                .put("nomeArquivoOriginal", nomeArquivoOriginal)
                .put("codigoMotivo", codigoMotivo)
                .put("ocorridoEm", ocorridoEm.toString());
        try {
            canal.basicPublish(EXCHANGE_DE_EVENTOS, ROUTING_KEY_DO_EVENTO, JSON,
                    evento.encode().getBytes(StandardCharsets.UTF_8));
        } catch (Exception erro) {
            throw new IllegalStateException("nao consegui publicar VideoFalhou", erro);
        }
    }

    private static void fecharSilenciosamente(Channel efemero) {
        if (efemero == null) {
            return;
        }
        try {
            efemero.close();
        } catch (Exception ignorado) {
            // O proprio broker ja fechou este canal ao recusar o passive declare.
        }
    }

    /** Package-private: os steps esperam pelo e-mail com o mesmo passo de sondagem. */
    static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(erro);
        }
    }
}
