package br.com.fiapx.extracao.core.exceptions;

/**
 * I/O de MinIO, disco cheio, worker morto no meio, ou qualquer exit code do ffmpeg que a
 * classificacao nao reconhece como permanente (ticket 006: falha fechada e conservadora —
 * exit desconhecido e transitorio). Quem trata esta excecao da <b>nack</b> com requeue e
 * deixa o {@code x-delivery-limit=3} da fila quorum decidir (ADR 0001).
 */
public class FalhaTransitoriaDeExtracaoException extends RuntimeException {

    public FalhaTransitoriaDeExtracaoException(String mensagem) {
        super(mensagem);
    }

    public FalhaTransitoriaDeExtracaoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
