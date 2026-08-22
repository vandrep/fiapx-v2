package br.com.fiapx.extracao.framework.service;

import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ArquivoGateway;
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
 */
@ApplicationScoped
public class ArquivoMinioAdapter implements ArquivoGateway {

    @Inject
    ArquivoMinioClient minioClient;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-videos")
    String bucketVideos;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    @Override
    public CompletableFuture<Path> baixarVideo(Path diretorio, String chaveVideo) {
        var destino = diretorio.resolve(Path.of(chaveVideo).getFileName());
        return minioClient.baixar(bucketVideos, chaveVideo, destino)
                .toCompletableFuture()
                .exceptionallyCompose(ArquivoMinioAdapter::comoFalhaTransitoria);
    }

    @Override
    public CompletableFuture<Void> gravarPacote(String chaveDestinoPacote, Path pacoteLocal) {
        return minioClient.gravar(bucketPacotes, chaveDestinoPacote, pacoteLocal)
                .toCompletableFuture()
                .exceptionallyCompose(ArquivoMinioAdapter::comoFalhaTransitoria);
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
