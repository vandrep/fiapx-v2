package br.com.fiapx.videos.core.entities;

import java.time.Instant;

/**
 * O que uma Extracao concluida produziu, do jeito que o evento {@code ExtracaoConcluida}
 * declara (docs/contratos/mensagens.md): viaja inteiro do consumidor de mensageria ate o
 * gateway, em vez de quatro parametros soltos (ticket 052). {@code tamanhoPacoteBytes} e
 * nomeado para nao se confundir com {@link Video#tamanhoBytes()}, que e o tamanho do Video
 * em si.
 */
public record ResultadoExtracao(Instant concluidaEm,
                                String chavePacote,
                                int quantidadeFrames,
                                long tamanhoPacoteBytes) {
}
