package br.com.fiapx.videos.core.exceptions;

/**
 * "Nao mais": a Extracao concluiu, mas o objeto ja nao esta no armazenamento. Vira 410.
 *
 * <p>O estado do Video continua CONCLUIDO — ele conta o que aconteceu com a Extracao, nao o
 * que ainda esta guardado (ticket 019).
 */
public class PacoteExpiradoException extends RuntimeException {

    public PacoteExpiradoException(String message) {
        super(message);
    }
}
