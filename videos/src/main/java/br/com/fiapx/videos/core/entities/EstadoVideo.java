package br.com.fiapx.videos.core.entities;

/**
 * Dono do grafo de transicoes do Video. Responde "esta transicao e legal?"; quem responde
 * "fui eu quem de fato mudou a linha?" e o UPDATE condicional do adapter (ADR 0002).
 */
public enum EstadoVideo {
    RECEBIDO,
    PROCESSANDO,
    CONCLUIDO,
    FALHOU;

    /**
     * Estado que a transicao para este exige como predecessor. E o valor que o use case
     * entrega ao gateway para virar o {@code WHERE} do UPDATE condicional — o grafo fica
     * declarado uma vez so, aqui.
     */
    public EstadoVideo predecessor() {
        return switch (this) {
            case PROCESSANDO -> RECEBIDO;
            case CONCLUIDO, FALHOU -> PROCESSANDO;
            case RECEBIDO -> throw new IllegalStateException(
                    "RECEBIDO é o estado de origem do Vídeo e não tem predecessor");
        };
    }

    /** Terminal significa "a Extração acabou", com Pacote ou com falha definitiva. */
    public boolean terminal() {
        return this == CONCLUIDO || this == FALHOU;
    }

    /**
     * Falso para reentrega fora de ordem, que e caminho esperado — inclusive quando a
     * reentrega repete o proprio terminal ja alcancado (duas entregas do mesmo
     * {@code ExtracaoFalhou} apos o Vídeo ja estar FALHOU, por exemplo). Transicao que o
     * grafo nao preve de jeito nenhum (de um terminal para o <b>outro</b>) e bug, e levanta
     * excecao.
     */
    public boolean transitaPara(EstadoVideo destino) {
        if (this == destino) {
            return false;
        }
        if (terminal() && destino.terminal()) {
            throw new IllegalStateException("Transição inexistente no grafo: " + this + " → " + destino);
        }
        return destino != RECEBIDO && destino.predecessor() == this;
    }
}
