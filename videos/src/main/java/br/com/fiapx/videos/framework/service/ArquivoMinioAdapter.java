package br.com.fiapx.videos.framework.service;

import br.com.fiapx.videos.core.entities.FormatoDoArquivo;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.framework.observabilidade.Rastro;
import br.com.fiapx.videos.framework.vertx.ContextoDeChamada;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 *
 * <p>A ida ao MinIO em si vive em {@link ArquivoMinioClient}, junto da retentativa do
 * ADR 0001 (ticket 048). Desde o ticket 061 essa retentativa e do Mutiny, e nao do
 * {@code @Retry}: o interceptor reagendava a chamada no contexto Vert.x do chamador e podia
 * prende-la la para sempre — ver o javadoc de la.
 *
 * <p>As duas idas ao MinIO ganham span (ticket 059): a extensao da AWS traz a instrumentacao do
 * SDK, mas nenhum span de S3 chegou ao Tempo num ciclo completo de Video, e sem estes dois o
 * upload de um Video de 200 MB era um vao mudo dentro do span do POST. O span cobre a operacao
 * inteira, retentativas incluidas — que e o que interessa a quem investiga.
 */
@ApplicationScoped
public class ArquivoMinioAdapter implements ArquivoGateway {

    @Inject
    ArquivoMinioClient minioClient;

    @Inject
    Rastro rastro;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    @Override
    public CompletableFuture<String> gravarVideo(UUID idVideo, String nome, Path arquivo) {
        var chave = chaveDoVideo(idVideo, nome);
        return rastro.emTorno("videos.gravar-video", () -> noContextoDeChamada(Uni.createFrom()
                .completionStage(() -> minioClient.gravar(bucketVideos, chave, arquivo))
                .replaceWith(chave)));
    }

    @Override
    public String chaveDoPacote(UUID idVideo) {
        return idVideo + ".zip";
    }

    @Override
    public CompletableFuture<Optional<Flow.Publisher<ByteBuffer>>> abrirPacote(String chavePacote) {
        return rastro.emTorno("videos.abrir-pacote", () -> noContextoDeChamada(Uni.createFrom()
                .completionStage(() -> minioClient.abrirSeExistir(bucketPacotes, chavePacote))));
    }

    /**
     * Devolve a continuacao ao contexto Vert.x de quem chamou.
     *
     * <p>O SDK da AWS completa seus futures na <b>propria</b> event loop, e um passo seguinte
     * que rode ali perde o contexto duplicado onde o Panache guarda a sessao: o
     * {@code EnviarVideoUseCase} grava no MinIO e so depois no banco, entao sem esta ponte o
     * INSERT morre com "No current Vertx context found". A alternativa seria o core saber a
     * ordem em que os gateways podem ser encadeados, que e exatamente o que ele nao deve saber.
     *
     * <p>A repeticao do {@link ArquivoMinioClient} fica <b>dentro</b> da {@code operacao} que
     * chega aqui, entao seja qual for a thread em que ela retome, o {@code emitOn} desta ponte
     * ainda a alcanca. Ate o ticket 090 este javadoc dizia que ela retomava "na thread do
     * scheduler do fault tolerance": esse scheduler saiu com o {@code @Retry} no ticket 061, e a
     * sexta regra do teste arquitetural proibe o interceptor desde entao.
     */
    private static <T> CompletableFuture<T> noContextoDeChamada(Uni<T> operacao) {
        return ContextoDeChamada.retomarNele(operacao).subscribeAsCompletionStage();
    }

    /**
     * A extensao original fica na chave: o `extracao` baixa para arquivo temporario e alguns
     * demuxers do ffmpeg se apoiam nela.
     */
    private String chaveDoVideo(UUID idVideo, String nome) {
        return idVideo + "/original." + FormatoDoArquivo.extensaoDe(nome);
    }
}
