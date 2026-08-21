package br.com.fiapx.videos.core.exceptions;

public class ItemNaoEncontradoException extends RuntimeException {

    public ItemNaoEncontradoException(long id) {
        super("Item não encontrado: " + id);
    }
}
