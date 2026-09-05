package br.com.fiapx.extracao.bdd;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A borda do `extracao` vista de fora, para os cenarios BDD: o broker de verdade que os Dev
 * Services sobem. O que entra e o comando em {@code fiapx.comandos}; o que sai sao os eventos
 * em {@code fiapx.eventos}. Nada aqui conhece controller, use case ou gateway — este objeto
 * fala AMQP puro, do mesmo jeito que os steps do `videos` falam HTTP pelo RestAssured
 * (AGENTS.md § BDD).
 *
 * <p>A fila {@link #FILA_DE_EVENTOS} e o unico artefato que existe so para o teste, e ela e
 * o analogo do cliente HTTP: um observador na saida, nao um endpoint acrescentado ao servico.
 * Em producao quem escuta essas mesmas routing keys e o `videos`.
 *
 * <p>Os exchanges sao declarados aqui com os mesmos argumentos que o conector SmallRye usa
 * ({@code topic}, duravel, sem auto-delete), entao a redeclaracao e idempotente e o teste nao
 * depende de o observador subir antes ou depois da aplicacao.
 */
@ApplicationScoped
public class BordaDeMensageria {

    private static final String EXCHANGE_DE_COMANDOS = "fiapx.comandos";
    private static final String EXCHANGE_DE_EVENTOS = "fiapx.eventos";
    private static final String ROUTING_KEY_DO_COMANDO = "extracao.extrair";
    private static final String FILA_DO_WORKER = "extracao.extrair";
    private static final String FILA_DE_EVENTOS = "bdd.extracao-eventos";
    /** `extracao.iniciada`, `extracao.concluida` e `extracao.falhou` — os tres do contrato. */
    private static final String EVENTOS_DO_EXTRACAO = "extracao.*";

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
    /** Eventos ja drenados no cenario corrente, por routing key. Zerado a cada cenario. */
    private final Map<String, JsonObject> eventosDoCenario = new HashMap<>();

    @PostConstruct
    void abrir() {
        var fabrica = new ConnectionFactory();
        fabrica.setHost(host);
        fabrica.setPort(porta);
        fabrica.setUsername(usuario);
        fabrica.setPassword(senha);
        try {
            conexao = fabrica.newConnection("bdd-extracao");
            canal = conexao.createChannel();
            canal.exchangeDeclare(EXCHANGE_DE_COMANDOS, "topic", true);
            canal.exchangeDeclare(EXCHANGE_DE_EVENTOS, "topic", true);
            // Exclusiva e auto-delete: some com a conexao ao fim da suite, e o RabbitMQ 4.x so
            // aceita fila transiente quando ela e exclusiva (`transient_nonexcl_queues` esta
            // deprecado e barrado por default).
            canal.queueDeclare(FILA_DE_EVENTOS, false, true, true, null);
            canal.queueBind(FILA_DE_EVENTOS, EXCHANGE_DE_EVENTOS, EVENTOS_DO_EXTRACAO);
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
     * Descarta o que sobrou do cenario anterior — e de qualquer outro {@code @QuarkusTest} do
     * modulo que tenha publicado nos mesmos exchanges, ja que o container do Dev Service e
     * reusado entre eles.
     */
    public void limparEventos() {
        eventosDoCenario.clear();
        try {
            canal.queuePurge(FILA_DE_EVENTOS);
        } catch (Exception erro) {
            throw new IllegalStateException("nao consegui limpar " + FILA_DE_EVENTOS, erro);
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
            if (filaExiste(FILA_DO_WORKER)) {
                return;
            }
            dormir(200);
        }
        throw new IllegalStateException(FILA_DO_WORKER + " nao apareceu em " + limite
                + ": o consumidor do extracao nao subiu");
    }

    private boolean filaExiste(String fila) {
        Channel efemero = null;
        try {
            efemero = conexao.createChannel();
            efemero.queueDeclarePassive(fila);
            return true;
        } catch (Exception naoExiste) {
            return false;
        } finally {
            fecharSilenciosamente(efemero);
        }
    }

    /** Publica {@code ExtrairVideo} pela mesma routing key que o `videos` usa em producao. */
    public void publicarComandoDeExtracao(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
        var comando = new JsonObject()
                .put("idVideo", idVideo.toString())
                .put("chaveVideo", chaveVideo)
                .put("chaveDestinoPacote", chaveDestinoPacote);
        try {
            canal.basicPublish(EXCHANGE_DE_COMANDOS, ROUTING_KEY_DO_COMANDO, JSON,
                    comando.encode().getBytes(StandardCharsets.UTF_8));
        } catch (Exception erro) {
            throw new IllegalStateException("nao consegui publicar o comando de extracao", erro);
        }
    }

    /**
     * Devolve o evento daquela routing key para aquele Video, drenando o que chegar no
     * caminho: os tres eventos do contrato saem em ordens diferentes conforme o cenario, e um
     * cenario pode perguntar pelo segundo antes do primeiro.
     */
    public JsonObject aguardarEvento(String routingKey, UUID idVideo, Duration limite) {
        long prazo = System.currentTimeMillis() + limite.toMillis();
        while (true) {
            var evento = eventosDoCenario.get(routingKey);
            if (evento != null) {
                return evento;
            }
            if (System.currentTimeMillis() >= prazo) {
                throw new AssertionError("o evento " + routingKey + " nao chegou em " + limite
                        + " para o video " + idVideo + "; recebidos ate agora: " + eventosDoCenario.keySet());
            }
            drenar(idVideo);
            dormir(200);
        }
    }

    private void drenar(UUID idVideo) {
        try {
            GetResponse resposta;
            while ((resposta = canal.basicGet(FILA_DE_EVENTOS, true)) != null) {
                var corpo = new JsonObject(new String(resposta.getBody(), StandardCharsets.UTF_8));
                if (idVideo.toString().equals(corpo.getString("idVideo"))) {
                    eventosDoCenario.put(resposta.getEnvelope().getRoutingKey(), corpo);
                }
            }
        } catch (Exception erro) {
            throw new IllegalStateException("nao consegui ler " + FILA_DE_EVENTOS, erro);
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

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(erro);
        }
    }
}
