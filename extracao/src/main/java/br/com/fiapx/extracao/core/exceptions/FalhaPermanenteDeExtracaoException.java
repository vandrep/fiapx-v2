package br.com.fiapx.extracao.core.exceptions;

import br.com.fiapx.extracao.core.entities.MotivoFalha;

/**
 * O video nao vai processar, e tentar de novo nao muda isso: arquivo corrompido, formato nao
 * suportado, sem stream de video, ou duracao acima do teto (ticket 011). Quem trata esta
 * excecao publica {@code ExtracaoFalhou} e da <b>ack</b> — nao gasta as tres entregas do
 * {@code x-delivery-limit} num caso que retry nao resolve
 * (docs/contratos/mensagens.md § Caminhos de falha).
 */
public class FalhaPermanenteDeExtracaoException extends RuntimeException {

    private final MotivoFalha motivo;
    private final String detalheTecnico;

    public FalhaPermanenteDeExtracaoException(MotivoFalha motivo, String detalheTecnico) {
        super(motivo + ": " + detalheTecnico);
        this.motivo = motivo;
        this.detalheTecnico = detalheTecnico;
    }

    public MotivoFalha motivo() {
        return motivo;
    }

    /** So para log — exit code e trecho do stderr, nunca chega ao usuario. */
    public String detalheTecnico() {
        return detalheTecnico;
    }
}
