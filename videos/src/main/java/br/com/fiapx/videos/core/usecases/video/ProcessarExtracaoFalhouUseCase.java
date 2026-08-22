package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Consumidor de {@code ExtracaoFalhou}: PROCESSANDO -> FALHOU, e a guarda de unicidade do
 * e-mail mora aqui (ADR 0001). {@code marcarFalha} so devolve o Vídeo preenchido quando
 * <b>esta</b> chamada de fato tirou a linha de PROCESSANDO — reentregas do mesmo evento
 * (o contrato nao garante ordem, e {@code x-delivery-limit} conta entregas) encontram o
 * Optional vazio e nao publicam nada. Tres entregas do mesmo evento produzem, portanto,
 * exatamente um {@code VideoFalhou}.
 */
public class ProcessarExtracaoFalhouUseCase {

    private final VideoGateway videoGateway;
    private final NotificacaoSender notificacaoSender;

    public ProcessarExtracaoFalhouUseCase(VideoGateway videoGateway, NotificacaoSender notificacaoSender) {
        this.videoGateway = videoGateway;
        this.notificacaoSender = notificacaoSender;
    }

    public CompletableFuture<Void> executar(Command command) {
        return videoGateway.marcarFalha(command.idVideo(), command.ocorridoEm(), command.motivo())
                .thenCompose(video -> video.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : publicarVideoFalhouEMarcar(video.get()));
    }

    private CompletableFuture<Void> publicarVideoFalhouEMarcar(Video video) {
        return notificacaoSender
                .enviarVideoFalhou(video.id(), video.dono(), video.nome(), video.motivo(), video.finalizadoEm())
                .thenCompose(ignorado -> videoGateway.marcarFalhaPublicada(video.id(), Instant.now()));
    }

    public record Command(UUID idVideo, MotivoFalha motivo, Instant ocorridoEm) {
    }
}
