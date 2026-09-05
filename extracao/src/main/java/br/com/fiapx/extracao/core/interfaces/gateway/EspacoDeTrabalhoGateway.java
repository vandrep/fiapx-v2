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

    /**
     * Abre um diretorio de trabalho <b>exclusivo desta tentativa</b> e devolve o caminho dele.
     * Duas tentativas do mesmo Video — a reentrega de {@code failure-strategy=requeue}, ou o
     * comando duplicado que o ADR 0003 tolera e que duas replicas podem pegar ao mesmo tempo —
     * recebem diretorios distintos, entao nenhuma herda frames meio-escritos da outra nem
     * apaga os dela (ticket 041).
     */
    CompletableFuture<Path> prepararNovo(UUID idVideo);

    /**
     * Apaga o diretorio <b>daquela tentativa</b>, e so ele. Chamado sempre, sucesso ou falha,
     * com o caminho que {@link #prepararNovo} devolveu.
     */
    CompletableFuture<Void> limpar(Path espacoDaTentativa);
}
