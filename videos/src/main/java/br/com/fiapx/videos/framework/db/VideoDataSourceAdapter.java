package br.com.fiapx.videos.framework.db;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * As leituras abrem a sessao <b>aqui</b>, e nao por {@code @WithSession} no Resource, por
 * duas razoes. A anotacao exige retorno {@code Uni}, e o download devolve {@code RestMulti};
 * e ela manteria a sessao aberta enquanto o Pacote inteiro trafega — segurar conexao de banco
 * durante 1,5 GB de streaming e desperdicio. {@code Panache.withSession} e reentrante, entao
 * conviver com o {@code @WithTransaction} do envio e seguro.
 */
@ApplicationScoped
public class VideoDataSourceAdapter implements VideoGateway {

    @Override
    public CompletableFuture<Void> adicionar(Video video) {
        return paraEntity(video).persist()
                .replaceWithVoid()
                .subscribeAsCompletionStage();
    }

    /**
     * Nao existe busca por id sozinho: o {@code dono_sub} entra no {@code WHERE}, e o 404 do
     * Video alheio sai do Optional vazio (ticket 009).
     */
    @Override
    public CompletableFuture<Optional<Video>> buscarPorIdEDono(UUID id, Dono dono) {
        return Panache.withSession(() ->
                        VideoEntity.<VideoEntity>find("id = ?1 and donoSub = ?2", id, dono.sub())
                                .firstResult()
                                .map(entity -> Optional.ofNullable(entity)
                                        .map(VideoDataSourceAdapter::paraDominio)))
                .subscribeAsCompletionStage();
    }

    @Override
    public CompletableFuture<Pagina<Video>> listarPorDono(Dono dono,
                                                          Optional<EstadoVideo> estado,
                                                          int pagina,
                                                          int tamanho) {
        return Panache.withSession(() -> {
            var consulta = estado
                    .map(filtro -> VideoEntity.<VideoEntity>find(
                            "donoSub = :dono and estado = :estado",
                            Sort.by("recebidoEm", Sort.Direction.Descending),
                            Parameters.with("dono", dono.sub()).and("estado", filtro)))
                    .orElseGet(() -> VideoEntity.<VideoEntity>find(
                            "donoSub = :dono",
                            Sort.by("recebidoEm", Sort.Direction.Descending),
                            Parameters.with("dono", dono.sub())));

            return Uni.combine().all()
                    .unis(consulta.page(pagina, tamanho).list(), consulta.count())
                    .with((entidades, total) -> new Pagina<>(
                            entidades.stream().map(VideoDataSourceAdapter::paraDominio).toList(),
                            pagina,
                            tamanho,
                            total));
        }).subscribeAsCompletionStage();
    }

    private static VideoEntity paraEntity(Video video) {
        var entity = new VideoEntity();
        entity.id = video.id();
        entity.donoSub = video.dono().sub();
        entity.emailDono = video.dono().email();
        entity.nome = video.nome();
        entity.tamanhoBytes = video.tamanhoBytes();
        entity.estado = video.estado();
        entity.recebidoEm = video.recebidoEm();
        entity.finalizadoEm = video.finalizadoEm();
        entity.chaveVideo = video.chaveVideo();
        entity.chavePacote = video.chavePacote();
        entity.quantidadeFrames = video.quantidadeFrames();
        entity.tamanhoPacoteBytes = video.tamanhoPacoteBytes();
        entity.motivo = video.motivo();
        return entity;
    }

    private static Video paraDominio(VideoEntity entity) {
        return Video.reconstituir(
                entity.id,
                entity.nome,
                entity.tamanhoBytes,
                new Dono(entity.donoSub, entity.emailDono),
                entity.chaveVideo,
                entity.estado,
                entity.recebidoEm,
                entity.finalizadoEm,
                entity.chavePacote,
                entity.quantidadeFrames,
                entity.tamanhoPacoteBytes,
                entity.motivo);
    }
}
