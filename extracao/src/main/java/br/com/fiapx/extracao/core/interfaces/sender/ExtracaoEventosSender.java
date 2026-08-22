package br.com.fiapx.extracao.core.interfaces.sender;

import br.com.fiapx.extracao.core.entities.MotivoFalha;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica os tres eventos que o `extracao` produz sobre uma Extracao (docs/contratos/
 * mensagens.md). O `core` fala em tipos de dominio; quem monta o {@code record} do contrato
 * e o adapter em {@code framework.dispatcher}.
 */
public interface ExtracaoEventosSender {

    /** "Aguardando na fila" e RECEBIDO, nao PROCESSANDO — este evento e o que faz o `videos`
     * transitar, porque so ele sabe que o worker de fato pegou o trabalho. */
    CompletableFuture<Void> enviarIniciada(UUID idVideo, Instant iniciadaEm);

    CompletableFuture<Void> enviarConcluida(UUID idVideo,
                                            String chavePacote,
                                            int quantidadeFrames,
                                            long tamanhoBytes,
                                            Instant concluidaEm);

    /** @param detalheTecnico so para log — exit code e trecho do stderr, nunca chega ao usuario */
    CompletableFuture<Void> enviarFalhou(UUID idVideo,
                                         MotivoFalha motivo,
                                         String detalheTecnico,
                                         Instant ocorridoEm);
}
