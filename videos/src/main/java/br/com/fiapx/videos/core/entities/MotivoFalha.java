package br.com.fiapx.videos.core.entities;

/**
 * Codigo estavel da falha de Extracao, nunca frase — a traducao para o usuario e do
 * `notificacao` (CONTEXT.md). Publico: a consulta a um Video FALHOU o devolve.
 */
public enum MotivoFalha {
    ARQUIVO_INVALIDO,
    FORMATO_NAO_SUPORTADO,
    SEM_FLUXO_DE_VIDEO,
    DURACAO_EXCEDIDA,
    TENTATIVAS_ESGOTADAS,

    /**
     * Ninguem publica este codigo: e onde o `videos` pousa um codigo que nao reconhece, para
     * que a estrategia aditiva do contrato de mensagens nao derrube uma mensagem vinda de um
     * `extracao` mais novo.
     */
    DESCONHECIDO;

    /** Leitura tolerante: codigo desconhecido vira DESCONHECIDO, nunca excecao. */
    public static MotivoFalha doCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return DESCONHECIDO;
        }
        for (MotivoFalha motivo : values()) {
            if (motivo.name().equals(codigo.trim())) {
                return motivo;
            }
        }
        return DESCONHECIDO;
    }
}
