package br.com.fiapx.extracao.bdd;

import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O `extracao` nao tem borda HTTP, mas tem borda: e o RabbitMQ. Estes steps entram por ela —
 * publicam o comando no exchange {@code fiapx.comandos} e leem os eventos que saem em
 * {@code fiapx.eventos} — e nunca tocam controller, use case ou gateway. O papel que o
 * RestAssured cumpre nos steps do `videos`, a {@link BordaDeMensageria} cumpre aqui
 * (AGENTS.md § BDD).
 *
 * <p>Nada e dublado: broker, MinIO e ffmpeg sao os reais que os Dev Services e o host
 * fornecem. O MinIO continua sendo preparado pelo SDK da AWS direto, e nao pela borda, porque
 * ele e <b>pre-condicao</b> do cenario (o `videos` que subiu o Video), nao o comportamento
 * sob teste.
 */
public class ExtracaoSteps {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    /**
     * Teto por evento. Cobre a subida do consumidor, o download do MinIO e o ffmpeg do fixture
     * de 3 segundos com folga larga; abaixo disso o cenario reprovaria por maquina lenta.
     */
    private static final Duration ESPERA = Duration.ofSeconds(60);

    @Inject
    BordaDeMensageria borda;

    @Inject
    S3AsyncClient s3;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    private UUID idVideo;
    private String chaveVideo;
    private String chaveDestinoPacote;

    @Before
    public void limparEstadoEntreCenarios() {
        idVideo = null;
        chaveVideo = null;
        chaveDestinoPacote = null;
        borda.limparEventos();
        borda.aguardarFilaDoWorker(ESPERA);
    }

    @Dado("que o vídeo {string} foi enviado para o MinIO")
    public void queOVideoFoiEnviadoParaOMinIO(String nomeDoFixture) {
        subirFixture(nomeDoFixture, "mp4");
    }

    @Dado("que o arquivo {string} foi enviado para o MinIO como se fosse um vídeo")
    public void queOArquivoFoiEnviadoComoSeFosseUmVideo(String nomeDoFixture) {
        subirFixture(nomeDoFixture, "txt");
    }

    private void subirFixture(String nomeDoFixture, String extensao) {
        idVideo = UUID.randomUUID();
        chaveVideo = idVideo + "/original." + extensao;
        chaveDestinoPacote = idVideo + ".zip";

        var caminho = FIXTURES.resolve(nomeDoFixture);
        if (!Files.exists(caminho)) {
            throw new UncheckedIOException(new IOException("fixture ausente: " + caminho.toAbsolutePath()));
        }

        s3.putObject(
                PutObjectRequest.builder().bucket(bucketVideos).key(chaveVideo).build(),
                AsyncRequestBody.fromFile(caminho)).join();
    }

    @Quando("o comando de extração é publicado na fila do extracao")
    public void oComandoDeExtracaoEPublicadoNaFila() {
        borda.publicarComandoDeExtracao(idVideo, chaveVideo, chaveDestinoPacote);
    }

    @Entao("o evento {string} é publicado para o videos")
    public void oEventoEPublicadoParaOVideos(String routingKey) {
        borda.aguardarEvento(routingKey, idVideo, ESPERA);
    }

    @E("o evento {string} é publicado para o videos com o motivo {string}")
    public void oEventoEPublicadoComOMotivo(String routingKey, String codigoMotivo) {
        JsonObject evento = borda.aguardarEvento(routingKey, idVideo, ESPERA);
        assertEquals(codigoMotivo, evento.getString("codigoMotivo"),
                () -> "codigoMotivo inesperado em " + routingKey + ": " + evento.encode());
        // detalheTecnico e so para log, mas e campo do contrato: sem ele o operador perde o
        // exit code do ffmpeg (docs/contratos/mensagens.md § ExtracaoFalhou).
        assertFalse(campo(evento, "detalheTecnico").toString().isBlank(),
                () -> "detalheTecnico nao pode chegar vazio: " + evento.encode());
    }

    @E("o evento {string} registra o instante em que o worker pegou o trabalho")
    public void oEventoRegistraOInstanteDeInicio(String routingKey) {
        JsonObject evento = borda.aguardarEvento(routingKey, idVideo, ESPERA);
        assertDoesNotThrow(() -> Instant.parse(campo(evento, "iniciadaEm").toString()),
                () -> "iniciadaEm precisa ser um instante ISO-8601: " + evento.encode());
    }

    @E("o evento {string} declara o Pacote que foi gravado")
    public void oEventoDeclaraOPacoteGravado(String routingKey) {
        JsonObject evento = borda.aguardarEvento(routingKey, idVideo, ESPERA);
        assertEquals(chaveDestinoPacote, evento.getString("chavePacote"),
                () -> "o evento precisa declarar a chave efetivamente gravada: " + evento.encode());
        assertTrue(((Number) campo(evento, "quantidadeFrames")).intValue() > 0,
                () -> "quantidadeFrames deveria contar os frames extraidos: " + evento.encode());
        assertTrue(((Number) campo(evento, "tamanhoBytes")).longValue() > 0,
                () -> "tamanhoBytes deveria ser o tamanho do .zip: " + evento.encode());
    }

    /**
     * Campo ausente reprova dizendo qual campo faltou, e nao com um NPE mudo la dentro do
     * {@code getInteger}: um campo que sumiu do evento e exatamente a quebra de contrato que
     * este cenario existe para pegar.
     */
    private static Object campo(JsonObject evento, String nome) {
        Object valor = evento.getValue(nome);
        assertNotNull(valor, () -> "o evento nao trouxe o campo " + nome + ": " + evento.encode());
        return valor;
    }

    @E("o Pacote é gravado no bucket de pacotes")
    public void oPacoteEGravadoNoBucketDePacotes() {
        assertTrue(objetoExiste(bucketPacotes, chaveDestinoPacote),
                () -> "esperava " + chaveDestinoPacote + " em " + bucketPacotes);
    }

    @E("nenhum Pacote é gravado no bucket de pacotes")
    public void nenhumPacoteEGravadoNoBucketDePacotes() {
        assertFalse(objetoExiste(bucketPacotes, chaveDestinoPacote),
                () -> "nao esperava " + chaveDestinoPacote + " em " + bucketPacotes);
    }

    private boolean objetoExiste(String bucket, String chave) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(chave).build()).get();
            return true;
        } catch (ExecutionException erro) {
            if (erro.getCause() instanceof NoSuchKeyException) {
                return false;
            }
            throw new RuntimeException(erro);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(erro);
        }
    }
}
