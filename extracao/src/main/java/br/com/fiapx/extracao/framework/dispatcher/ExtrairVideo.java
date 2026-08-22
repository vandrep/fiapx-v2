package br.com.fiapx.extracao.framework.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Comando {@code videos} -> {@code extracao}, routing key {@code extracao.extrair}
 * (docs/contratos/mensagens.md). Sem envelope: o tipo esta na routing key, nao no corpo.
 * Copia propria deste servico — nao ha modulo `shared` (ticket 007).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
}
