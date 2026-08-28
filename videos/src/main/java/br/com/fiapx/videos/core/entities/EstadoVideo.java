package br.com.fiapx.videos.core.entities;

import java.util.Set;

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
     * Estados que a transicao para este aceita como predecessor. E o conjunto que o use case
     * entrega ao gateway para virar o {@code IN} do UPDATE condicional — o grafo fica
     * declarado uma vez so, aqui.
     *
     * <p>Os terminais aceitam <b>dois</b>, e nao um: {@code ExtracaoIniciada} e
     * {@code ExtracaoConcluida} viajam em filas independentes e o contrato nao promete ordem
     * entre elas (docs/contratos/mensagens.md). Exigir PROCESSANDO deixava a terminal que
     * chegasse primeiro alterar zero linhas e receber ack, prendendo o Video em PROCESSANDO
     * para sempre com o Pacote ja gravado — medido em 11/400 sob pico (ticket 027, ADR 0002).
     * PROCESSANDO e informacao de acompanhamento, nao portao.
     */
    public Set<EstadoVideo> predecessores() {
        return switch (this) {
            case PROCESSANDO -> Set.of(RECEBIDO);
            case CONCLUIDO, FALHOU -> Set.of(RECEBIDO, PROCESSANDO);
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
        return destino != RECEBIDO && destino.predecessores().contains(this);
    }
}
