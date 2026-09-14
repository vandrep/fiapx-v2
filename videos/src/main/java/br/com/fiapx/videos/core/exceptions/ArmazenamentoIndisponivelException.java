package br.com.fiapx.videos.core.exceptions;

/**
 * O armazenamento recusou a gravacao do Video enviado, ja depois das repeticoes do ADR 0001.
 * E a primeira escrita do envio, entao nada mais foi gravado e o sistema nao assumiu o Video:
 * vira 503 na borda, e nao 500 (ticket 108).
 */
public class ArmazenamentoIndisponivelException extends RuntimeException {

    public ArmazenamentoIndisponivelException(String message, Throwable cause) {
        super(message, cause);
    }
}
