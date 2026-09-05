package br.com.fiapx.videos.framework.dispatcher;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.core.usecases.video.EnviarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.PublicarExtrairVideo;
import br.com.fiapx.videos.core.usecases.video.ReconciliarPublicacoesPendentesUseCase;
import br.com.fiapx.videos.framework.db.VideoDataSourceAdapter;
import br.com.fiapx.videos.interfaces.controllers.ReconciliacaoController;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A janela real do ticket 040: entre o commit da linha e o primeiro publish do
 * {@code ExtrairVideo}. A interrupcao entra pelo {@link ExtracaoSender}, que e onde o envio
 * ao broker acontece, e todo o resto e o caminho de producao — Postgres de verdade,
 * {@link ReconciliacaoController} vindo do CDI e o RabbitMQ do outro lado, espiado por uma
 * fila propria ligada ao mesmo exchange que o {@code extracao} escuta.
 *
 * <p>O par com o teste em memoria de {@code ReconciliarPublicacoesPendentesUseCaseTest} e
 * deliberado: la a garantia e do algoritmo, aqui e da infraestrutura que ele usa.
 */
@QuarkusTest
class ReconciliacaoAposPublicacaoInterrompidaTest {

    private static final Dono DONO = new Dono("sub-reconciliacao", "reconciliacao@exemplo.com");
    private static final String EXCHANGE_DE_COMANDOS = "fiapx.comandos";
    private static final String ROUTING_KEY = "extracao.extrair";
    private static final String INTERRUPCAO = "conexao caiu antes do publish";
    private static final Duration PRAZO_ATE_NAO_HAVER_COMANDO = Duration.ofMillis(300);
    private static final Duration PRAZO_DO_REPUBLICADO = Duration.ofSeconds(10);

    @Inject
    VideoDataSourceAdapter adapter;

    @Inject
    ArquivoGateway arquivoGateway;

    /** O bean de producao: a recuperacao tem de vir do mesmo grafo que o scheduler chama. */
    @Inject
    ReconciliacaoController reconciliacaoController;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Pool pool;

    @ConfigProperty(name = "rabbitmq-host")
    String rabbitmqHost;

    @ConfigProperty(name = "rabbitmq-port")
    int rabbitmqPort;

    @Test
    void publicacaoInterrompidaDepoisDoCommitNaoMarcaEAVarreduraRepublica(@TempDir Path diretorio) throws Throwable {
        var arquivo = Files.write(diretorio.resolve("interrompido.mp4"), "video interrompido".getBytes());

        try (var broker = new BrokerDeTeste(rabbitmqHost, rabbitmqPort, objectMapper)) {
            var espia = broker.espiar(EXCHANGE_DE_COMANDOS, ROUTING_KEY);
            var id = enviarComPublicacaoInterrompida(arquivo);

            assertFalse(temMarcaDeComandoPublicado(id),
                    "publicacao sem confirmacao nao pode deixar marca de sucesso");
            assertNull(comandoDoVideo(espia, id, PRAZO_ATE_NAO_HAVER_COMANDO),
                    "a interrupcao acontece antes de o comando chegar ao broker");

            envelhecerAlemDaFolgaDaVarredura(id);
            assertTrue(reconciliar().comandos() >= 1, "a varredura tinha de republicar algum comando pendente");

            var comando = comandoDoVideo(espia, id, PRAZO_DO_REPUBLICADO);
            assertNotNull(comando, "o ExtrairVideo republicado tinha de chegar ao broker");
            assertEquals(arquivoGateway.chaveDoPacote(id), comando.chaveDestinoPacote());
            assertTrue(temMarcaDeComandoPublicado(id), "republicado e confirmado, a marca passa a valer");
        }
    }

    private ExtrairVideo comandoDoVideo(BrokerDeTeste.Espia espia, UUID id, Duration prazo) throws Exception {
        return espia.esperar(ExtrairVideo.class, comando -> id.equals(comando.idVideo()), prazo);
    }

    /**
     * O envio percorre gravacao do arquivo, insercao e publicacao; a falha entra no ultimo
     * passo, com a linha ja commitada por {@link VideoDataSourceAdapter#adicionar}. O id sai
     * pelo proprio sender porque o {@link EnviarVideoUseCase} nao chega a devolver o Video.
     */
    private UUID enviarComPublicacaoInterrompida(Path arquivo) throws Throwable {
        var id = new UUID[1];
        ExtracaoSender interrompido = (idVideo, chaveVideo, chaveDestinoPacote) -> {
            id[0] = idVideo;
            return CompletableFuture.failedFuture(new IllegalStateException(INTERRUPCAO));
        };
        var envio = new EnviarVideoUseCase(arquivoGateway, adapter,
                new PublicarExtrairVideo(arquivoGateway, interrompido, adapter), video -> { });
        var command = new EnviarVideoUseCase.Command(
                "interrompido.mp4", "video/mp4", Files.size(arquivo), arquivo, DONO);

        var falha = falhaDe(() -> noContextoDoVertx(() ->
                Uni.createFrom().completionStage(() -> envio.executar(command))));
        assertNotNull(falha, "o envio tinha de propagar a interrupcao da publicacao");
        assertEquals(INTERRUPCAO, raizDe(falha).getMessage(),
                "a falha propagada tem de ser a injetada, e nao outra qualquer");
        assertNotNull(id[0], "a interrupcao so vale se o caminho chegou ao publish");
        return id[0];
    }

    /**
     * A varredura ignora o que foi recebido ha menos de um minuto — a folga que a separa do
     * envio ainda em curso. Envelhecer a linha e o equivalente deterministico de esperar a
     * folga passar.
     */
    private void envelhecerAlemDaFolgaDaVarredura(UUID id) throws Throwable {
        var alteradas = noContextoDoVertx(() -> pool.preparedQuery(
                        "update video set recebido_em = recebido_em - interval '2 minutes' where id = $1")
                .execute(Tuple.of(id))).rowCount();
        assertEquals(1, alteradas, "a linha do Video interrompido tinha de estar commitada");
    }

    private ReconciliarPublicacoesPendentesUseCase.Republicacoes reconciliar() throws Throwable {
        return noContextoDoVertx(
                () -> Uni.createFrom().completionStage(() -> reconciliacaoController.reconciliar()));
    }

    /** Lido por conexao propria, e como boolean: o tipo da coluna e assunto do driver. */
    private boolean temMarcaDeComandoPublicado(UUID id) throws Throwable {
        return noContextoDoVertx(() -> pool.preparedQuery(
                        "select comando_publicado_em is not null from video where id = $1")
                .execute(Tuple.of(id))).iterator().next().getBoolean(0);
    }

    /** A falha que o trabalho levantou, ou {@code null} se ele completou. */
    private static Throwable falhaDe(TrabalhoQuePodeFalhar trabalho) {
        try {
            trabalho.executar();
            return null;
        } catch (Throwable falha) {
            return falha;
        }
    }

    /** O {@code subscribeAndAwait} embrulha; o que interessa julgar e a causa original. */
    private static Throwable raizDe(Throwable falha) {
        var raiz = falha;
        while (raiz.getCause() != null) {
            raiz = raiz.getCause();
        }
        return raiz;
    }

    private interface TrabalhoQuePodeFalhar {
        void executar() throws Throwable;
    }

    /**
     * O Panache exige contexto Vert.x, e o teste precisa ficar num thread que possa bloquear
     * esperando o broker — dai o contexto duplicado em vez de {@code @RunOnVertxContext}.
     */
    private static <T> T noContextoDoVertx(Supplier<Uni<T>> trabalho) throws Throwable {
        return VertxContextSupport.subscribeAndAwait(trabalho);
    }
}
