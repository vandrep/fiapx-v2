package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.extracao.framework.observabilidade.Rastro;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * O MinIO por API S3, visto pelo `core`. Ao contrario do `videos`, este adapter nao conhece
 * nenhuma convencao de chave — recebe as chaves prontas na mensagem {@code ExtrairVideo}
 * (ticket 011, docs/contratos/mensagens.md).
 *
 * <p>Streaming ponta a ponta com arquivo real em disco, nunca bytes em memoria (ticket 005 e
 * 011). O retry de fato (ADR 0001) vive em {@link ArquivoMinioClient} — separado porque
 * {@code @Retry} exige {@code CompletionStage} e nao pode ser chamado do mesmo bean (ver
 * javadoc la).
 *
 * <p>Os dois metodos ganham span (ticket 059) porque o MinIO nao aparece sozinho: a extensao da
 * AWS traz a instrumentacao do SDK, mas nenhum span de S3 chegou ao Tempo num ciclo completo de
 * Video. Sem estes dois, o download do Video e o upload do Pacote — os unicos trechos de rede de
 * uma Extracao — ficavam vaos mudos dentro do span do worker, e "onde este Video parou" nao
 * tinha resposta justamente onde ela costuma estar.
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
    public CompletableFuture<Path> baixarVideo(Path diretorio, String chaveVideo) {
        var destino = diretorio.resolve(Path.of(chaveVideo).getFileName());
        return rastro.emTorno("extracao.baixar-video", () -> minioClient.baixar(bucketVideos, chaveVideo, destino)
                .toCompletableFuture()
                .exceptionallyCompose(ArquivoMinioAdapter::comoFalhaTransitoria));
    }

    @Override
    public CompletableFuture<Void> gravarPacote(String chaveDestinoPacote, Path pacoteLocal) {
        return rastro.emTorno("extracao.gravar-pacote",
                () -> minioClient.gravar(bucketPacotes, chaveDestinoPacote, pacoteLocal)
                        .toCompletableFuture()
                        .exceptionallyCompose(ArquivoMinioAdapter::comoFalhaTransitoria));
    }

    /**
     * Qualquer falha do MinIO aqui e transitoria por definicao: nao ha "objeto ausente e
     * esperado" como no download do `videos` (ticket 019) — um Video ou um destino que nao
     * existe e sempre infraestrutura fora do ar ou mensagem mal formada, nunca um caso de
     * negocio a distinguir.
     */
    private static <T> CompletableFuture<T> comoFalhaTransitoria(Throwable falha) {
        var causa = falha instanceof CompletionException ? falha.getCause() : falha;
        return CompletableFuture.failedFuture(new FalhaTransitoriaDeExtracaoException("MinIO: " + causa.getMessage(), causa));
    }
}
