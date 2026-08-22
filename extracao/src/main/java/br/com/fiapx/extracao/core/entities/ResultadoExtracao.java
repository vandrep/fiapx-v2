package br.com.fiapx.extracao.core.entities;

/**
 * O que uma Extracao bem-sucedida produziu: a contagem de frames (conferida contra a
 * duracao do `ffprobe`, ticket 006) e o tamanho do `.zip` gravado, que o evento
 * {@code ExtracaoConcluida} carrega (docs/contratos/mensagens.md).
 */
public record ResultadoExtracao(int quantidadeFrames, long tamanhoBytes) {
}
