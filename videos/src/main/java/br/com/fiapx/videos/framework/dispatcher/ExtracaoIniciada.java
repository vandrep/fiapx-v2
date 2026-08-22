package br.com.fiapx.videos.framework.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code extracao} -> {@code videos}: o worker pegou o trabalho de fato — "aguardando
 * na fila" e RECEBIDO, nao PROCESSANDO (docs/contratos/mensagens.md).
 *
 * <p>Leitura tolerante: campo novo e sempre opcional (versionamento so-aditivo do contrato).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtracaoIniciada(UUID idVideo, Instant iniciadaEm) {
}
