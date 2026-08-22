package br.com.fiapx.videos.core.exceptions;

/** Campo `arquivo` ausente ou vazio no multipart. Vira 400. */
public class ArquivoAusenteException extends RuntimeException {

    public ArquivoAusenteException(String message) {
        super(message);
    }
}
