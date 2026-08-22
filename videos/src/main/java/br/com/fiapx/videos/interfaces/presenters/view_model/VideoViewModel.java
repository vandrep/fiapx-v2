package br.com.fiapx.videos.interfaces.presenters.view_model;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;

import java.time.Instant;
import java.util.UUID;

/**
 * A representacao de Video como o contrato HTTP a publica.
 *
 * <p>{@code concluidoEm} e o nome publicado de {@code finalizado_em}: no banco o nome e
 * honesto (ele tambem marca o FALHOU), na saida vale o nome que o contrato ja publicou.
 * Traduzir dominio -> JSON e o trabalho do presenter.
 *
 * <p>{@code motivo} sai como <b>codigo</b>, nunca frase: a frase que o usuario le e do
 * `notificacao`, e uma segunda traducao aqui divergiria da dele.
 */
public record VideoViewModel(UUID id,
                             String nome,
                             EstadoVideo estado,
                             long tamanhoBytes,
                             Instant recebidoEm,
                             Instant concluidoEm,
                             MotivoFalha motivo) {
}
