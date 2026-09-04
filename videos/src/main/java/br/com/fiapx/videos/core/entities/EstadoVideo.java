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

    private static final System.Logger LOG = System.getLogger(EstadoVideo.class.getName());

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
     * reentrega chega depois de um estado terminal. Entre terminais, o primeiro vence e a
     * corrida e registrada para diagnostico operacional (ticket 031, ADR 0002).
     */
    public boolean transitaPara(EstadoVideo destino) {
        if (this == destino) {
            return false;
        }
        if (terminal() && destino.terminal()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Transição concorrente entre terminais ignorada: {0} → {1}", this, destino);
            return false;
        }
        return destino != RECEBIDO && destino.predecessores().contains(this);
    }
}
