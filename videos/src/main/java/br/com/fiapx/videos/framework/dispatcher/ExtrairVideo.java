package br.com.fiapx.videos.framework.dispatcher;

import java.util.UUID;

/**
 * Comando {@code videos} -> {@code extracao}, routing key {@code extracao.extrair}
 * (docs/contratos/mensagens.md). Sem envelope: o tipo esta na routing key, nao no corpo.
 */
public record ExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
}
