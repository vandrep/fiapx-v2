package br.com.fiapx.notificacao.core.exceptions;

public class ItemNaoEncontradoException extends RuntimeException {

    public ItemNaoEncontradoException(long id) {
        super("Item não encontrado: " + id);
    }
}
