package br.com.fiapx.extracao.core.exceptions;

/**
 * A Extração já falhou permanentemente, mas o evento que comunica esse desfecho não pôde ser
 * publicado. A borda usa esta classificação para rejeitar o comando sem requeue e encaminhá-lo
 * à DLQ, em vez de executar novamente um trabalho que já tem resultado definitivo (ticket 029).
 */
public class FalhaAoPublicarExtracaoFalhouException extends RuntimeException {

    public FalhaAoPublicarExtracaoFalhouException(Throwable causa) {
        super("nao foi possivel publicar ExtracaoFalhou", causa);
    }
}
