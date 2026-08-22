package br.com.fiapx.videos.framework.dispatcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code extracao} -> {@code videos}. {@code chavePacote} volta mesmo tendo sido
 * enviada no comando: o evento declara o que foi de fato gravado
 * (docs/contratos/mensagens.md). Leitura tolerante: versionamento e so-aditivo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtracaoConcluida(UUID idVideo,
                                String chavePacote,
                                int quantidadeFrames,
                                long tamanhoBytes,
                                Instant concluidaEm) {
}
