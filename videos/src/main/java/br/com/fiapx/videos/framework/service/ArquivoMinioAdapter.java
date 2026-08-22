package br.com.fiapx.videos.framework.service;

import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.reactivestreams.FlowAdapters;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

/**
 * O MinIO por API S3. Aqui — e so aqui — vive a convencao de nomes de chave (ticket 011):
 * bucket {@code videos} com {@code {idVideo}/original.{ext}}, bucket {@code pacotes} com
 * {@code {idVideo}.zip}. Mudar o formato nao toca contrato nenhum.
 *
 * <p>A chave <b>nao carrega o dono</b>: a autoridade sobre propriedade e o {@code dono_sub}
 * no Postgres, e repeti-la aqui criaria uma segunda fonte de verdade.
 *
 * <p>Streaming ponta a ponta, sem {@code toBytes}/{@code fromBytes} (ticket 005): um Video
 * pode ter 200 MB e um Pacote 1,5 GB.
 */
@ApplicationScoped
public class ArquivoMinioAdapter implements ArquivoGateway {

    @Inject
    S3AsyncClient s3;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    @Override
    public CompletableFuture<String> gravarVideo(UUID idVideo, String nome, Path arquivo) {
        var chave = chaveDoVideo(idVideo, nome);
        var requisicao = PutObjectRequest.builder()
                .bucket(bucketVideos)
                .key(chave)
                .build();
        return noContextoDeChamada(
                Uni.createFrom()
                        .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(arquivo)))
                        .map(resposta -> chave));
    }

    @Override
    public String chaveDoPacote(UUID idVideo) {
        return idVideo + ".zip";
    }

    @Override
    public CompletableFuture<Optional<Flow.Publisher<ByteBuffer>>> abrirPacote(String chavePacote) {
        var requisicao = GetObjectRequest.builder()
                .bucket(bucketPacotes)
                .key(chavePacote)
                .build();
        return noContextoDeChamada(Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toPublisher())
                        .<Optional<Flow.Publisher<ByteBuffer>>>thenApply(
                                publicador -> Optional.of(FlowAdapters.toFlowPublisher(publicador)))
                        .exceptionally(ArquivoMinioAdapter::vazioSeAusente)));
    }

    /**
     * Devolve a continuacao ao contexto Vert.x de quem chamou.
     *
     * <p>O SDK da AWS completa seus futures na <b>propria</b> event loop, e um passo seguinte
     * que rode ali perde o contexto duplicado onde o Panache guarda a sessao — o
     * {@code EnviarVideoUseCase} grava no MinIO e so depois no banco, entao sem esta ponte o
     * INSERT morre com "No current Vertx context found". A alternativa seria o core saber a
     * ordem em que os gateways podem ser encadeados, que e exatamente o que ele nao deve saber.
     */
    private static <T> CompletableFuture<T> noContextoDeChamada(Uni<T> operacao) {
        Context contexto = Vertx.currentContext();
        if (contexto == null) {
            return operacao.subscribeAsCompletionStage();
        }
        return operacao
                .emitOn(comando -> contexto.runOnContext(ignorado -> comando.run()))
                .subscribeAsCompletionStage();
    }

    /**
     * A extensao original fica na chave: o `extracao` baixa para arquivo temporario e alguns
     * demuxers do ffmpeg se apoiam nela.
     */
    private String chaveDoVideo(UUID idVideo, String nome) {
        return idVideo + "/original." + FormatoDoArquivo.extensaoDe(nome);
    }

    /**
     * <b>So</b> o objeto ausente vira Optional vazio. Mapear qualquer falha do MinIO para
     * "expirou" faria um MinIO fora do ar mandar o cliente desistir para sempre; erro de
     * infraestrutura tem de continuar 500 (ticket 019).
     */
    private static Optional<Flow.Publisher<ByteBuffer>> vazioSeAusente(Throwable falha) {
        var causa = falha instanceof CompletionException ? falha.getCause() : falha;
        if (causa instanceof NoSuchKeyException) {
            return Optional.empty();
        }
        throw causa instanceof RuntimeException erro ? erro : new CompletionException(causa);
    }
}
