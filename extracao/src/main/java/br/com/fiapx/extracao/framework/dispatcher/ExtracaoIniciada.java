package br.com.fiapx.extracao.framework.dispatcher;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code extracao} -> {@code videos}: o worker pegou o trabalho de fato — "aguardando
 * na fila" e RECEBIDO, nao PROCESSANDO (docs/contratos/mensagens.md).
 */
public record ExtracaoIniciada(UUID idVideo, Instant iniciadaEm) {
}
