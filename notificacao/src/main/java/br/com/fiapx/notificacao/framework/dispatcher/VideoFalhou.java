package br.com.fiapx.notificacao.framework.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code videos} -> {@code notificacao}, publicado apenas pela transicao que de fato
 * mudou a linha do Video — a guarda de unicidade do e-mail (ADR 0001,
 * docs/contratos/mensagens.md). Leitura tolerante: versionamento e so-aditivo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoFalhou(UUID idVideo,
                          String donoSub,
                          String emailDono,
                          String nomeArquivoOriginal,
                          String codigoMotivo,
                          Instant ocorridoEm) {
}
