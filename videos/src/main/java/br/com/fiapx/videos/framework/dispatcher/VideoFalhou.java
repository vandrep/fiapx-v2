package br.com.fiapx.videos.framework.dispatcher;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code videos} -> {@code notificacao}, publicado apenas pela transicao que de fato
 * mudou a linha do Vídeo — e nao estado no {@code notificacao} — que impede o e-mail
 * duplicado (ADR 0001, docs/contratos/mensagens.md).
 */
public record VideoFalhou(UUID idVideo,
                          String donoSub,
                          String emailDono,
                          String nomeArquivoOriginal,
                          String codigoMotivo,
                          Instant ocorridoEm) {
}
