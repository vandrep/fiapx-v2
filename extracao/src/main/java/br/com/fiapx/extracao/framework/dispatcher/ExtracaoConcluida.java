package br.com.fiapx.extracao.framework.dispatcher;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code extracao} -> {@code videos}. {@code chavePacote} volta mesmo tendo sido
 * recebida no comando: o evento declara o que foi de fato gravado
 * (docs/contratos/mensagens.md).
 */
public record ExtracaoConcluida(UUID idVideo,
                                String chavePacote,
                                int quantidadeFrames,
                                long tamanhoBytes,
                                Instant concluidaEm) {
}
