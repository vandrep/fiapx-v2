package br.com.fiapx.videos.core.exceptions;

/** Extensao ou content-type fora da lista aceita. Vira 415 na borda. */
public class FormatoNaoSuportadoException extends RuntimeException {

    public FormatoNaoSuportadoException(String message) {
        super(message);
    }
}
