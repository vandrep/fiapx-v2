package br.com.fiapx.videos.core.interfaces.presenter.dto;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma representacao de Video, nao tres: o corpo do POST, o do GET individual e cada item da
 * listagem sao o mesmo objeto (contrato HTTP).
 *
 * <p>Nao carrega chave de objeto, {@code quantidadeFrames} nem {@code tamanhoPacoteBytes} —
 * o primeiro e detalhe interno, os outros dois nao estao no contrato.
 */
public record VideoDTO(UUID id,
                       String nome,
                       EstadoVideo estado,
                       long tamanhoBytes,
                       Instant recebidoEm,
                       Instant finalizadoEm,
                       MotivoFalha motivo) {

    public static VideoDTO de(Video video) {
        return new VideoDTO(
                video.id(),
                video.nome(),
                video.estado(),
                video.tamanhoBytes(),
                video.recebidoEm(),
                video.finalizadoEm(),
                video.motivo());
    }
}
