package br.com.fiapx.extracao.core.entities;

/**
 * Codigo estavel da falha de Extracao, nunca frase — a traducao para o usuario e do
 * `notificacao` (CONTEXT.md, docs/contratos/mensagens.md). O `extracao` e quem publica: ao
 * contrario do `videos`, nao precisa de um valor DESCONHECIDO, porque nunca consome este
 * codigo de volta.
 */
public enum MotivoFalha {
    ARQUIVO_INVALIDO,
    FORMATO_NAO_SUPORTADO,
    SEM_FLUXO_DE_VIDEO,
    DURACAO_EXCEDIDA,
    TENTATIVAS_ESGOTADAS
}
