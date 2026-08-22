package br.com.fiapx.extracao.core.interfaces.gateway;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * O scratch em disco visto pelo dominio (ticket 011): o worker morre no meio por desenho, e
 * "o processo termina e limpa" nao vale aqui. Duas camadas de limpeza — {@link #limpar} roda
 * em {@code finally} por mensagem; a varredura de orfaos de crash no boot e responsabilidade
 * do adapter, disparada por {@code StartupEvent} em `framework`, sem passar pelo `core`.
 */
public interface EspacoDeTrabalhoGateway {

    /** Apaga-e-recria o diretorio de trabalho do Video: a tentativa nova nao herda frames
     * meio-escritos de uma tentativa anterior (`failure-strategy=requeue` reentrega a mesma
     * mensagem). */
    CompletableFuture<Path> prepararNovo(UUID idVideo);

    /** Apaga o diretorio de trabalho. Chamado sempre, sucesso ou falha. */
    CompletableFuture<Void> limpar(UUID idVideo);
}
