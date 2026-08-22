package br.com.fiapx.extracao.bdd;

import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
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
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `extracao` nao tem borda HTTP: o {@link ExtracaoController} cumpre o mesmo papel de
 * fronteira que um {@code Resource} cumpre num servico com borda (docs/contratos/
 * mensagens.md § Camadas — o consumidor de mensageria e "analogo ao Resource"). Os cenarios
 * chamam o controller diretamente, com ffmpeg e MinIO reais (Dev Services) — nunca dubles.
 *
 * <p>Fora de escopo aqui: o transporte RabbitMQ em si (fila, DLQ, ack/nack), que os testes
 * de {@code ArchitectureConstraintsTest} e a topologia verificada manualmente contra o
 * management API cobrem (ver Resolucao do ticket 015).
 */
public class ExtracaoSteps {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Inject
    ExtracaoController extracaoController;

    @Inject
    S3AsyncClient s3;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    private UUID idVideo;
    private String chaveVideo;
    private String chaveDestinoPacote;
    private Exception excecaoCapturada;

    @Before
    public void limparEstadoEntreCenarios() {
        idVideo = null;
        chaveVideo = null;
        chaveDestinoPacote = null;
        excecaoCapturada = null;
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

    @Quando("o extracao processa o comando de extração para esse vídeo")
    public void oExtracaoProcessaOComandoDeExtracao() {
        try {
            extracaoController.processarExtrairVideo(idVideo, chaveVideo, chaveDestinoPacote).get();
        } catch (InterruptedException | ExecutionException erro) {
            excecaoCapturada = erro;
        }
    }

    @Entao("o processamento completa sem lançar exceção")
    public void oProcessamentoCompletaSemLancarExcecao() {
        assertDoesNotThrow(() -> {
            if (excecaoCapturada != null) {
                throw excecaoCapturada;
            }
        });
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
