package br.com.fiapx.notificacao.core.entities;

/**
 * Codigo estavel repassado por {@code VideoFalhou} (docs/contratos/mensagens.md). O
 * `notificacao` e a unica parte do sistema que escolhe a frase em portugues que o usuario le
 * — nem `extracao`, nem `videos` traduzem este codigo.
 *
 * <p>{@link #DESCONHECIDO} nao e so defesa contra um codigo futuro que este servico ainda nao
 * conhece: e o mesmo valor que o `videos` pousa quando recebe de um `extracao` mais novo um
 * codigo que ele tambem nao reconhece (ticket 009), e o `videos` repassa esse valor como
 * {@code codigoMotivo} de {@code VideoFalhou} sem reescreve-lo. Ou seja: DESCONHECIDO chega
 * aqui como valor legitimo, nao so em teoria.
 */
public enum MotivoFalha {

    ARQUIVO_INVALIDO("o arquivo enviado não pôde ser lido como vídeo"),
    FORMATO_NAO_SUPORTADO("o formato do vídeo não é suportado"),
    SEM_FLUXO_DE_VIDEO("o arquivo enviado não contém um fluxo de vídeo"),
    DURACAO_EXCEDIDA("o vídeo ultrapassa o limite de duração permitido"),
    TENTATIVAS_ESGOTADAS("o processamento falhou repetidamente e as tentativas se esgotaram"),
    DESCONHECIDO("não foi possível determinar o motivo da falha");

    private final String frase;

    MotivoFalha(String frase) {
        this.frase = frase;
    }

    /** A unica frase em portugues que o usuario le para este motivo. */
    public String paraFrase() {
        return frase;
    }

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
