package br.com.fiapx.videos.core.entities;

import br.com.fiapx.videos.core.exceptions.FormatoNaoSuportadoException;

import java.util.Locale;
import java.util.Set;

/**
 * A extensao do arquivo enviado, ja conferida contra a lista aceita (ticket 011).
 *
 * <p>A validacao e <b>declarativa, nao probatoria</b>: ela pergunta "voce quis mesmo mandar
 * isso?". A prova de que o arquivo e um video decodificavel mora no `extracao`, via exit
 * code do ffmpeg — medi-la aqui exigiria ffmpeg na imagem do `videos`.
 *
 * <p>Vive no core, e nao no adapter, porque a extensao tambem e o que o `ArquivoGateway`
 * preserva na chave do MinIO: alguns demuxers do ffmpeg se apoiam nela.
 */
public record FormatoDoArquivo(String extensao) {

    private static final Set<String> EXTENSOES_ACEITAS = Set.of("mp4", "avi", "mov", "mkv", "webm");
    private static final String PREFIXO_CONTENT_TYPE = "video/";

    public static FormatoDoArquivo aceito(String nome, String contentType) {
        var extensao = extensaoDe(nome);
        if (!EXTENSOES_ACEITAS.contains(extensao)) {
            throw new FormatoNaoSuportadoException(
                    "Extensão não suportada: " + (extensao.isEmpty() ? nome : "." + extensao)
                            + ". Aceitas: mp4, avi, mov, mkv, webm");
        }
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(PREFIXO_CONTENT_TYPE)) {
            throw new FormatoNaoSuportadoException(
                    "Content-type não suportado: " + contentType + ". Esperado video/*");
        }
        return new FormatoDoArquivo(extensao);
    }

    /** Vazio quando o nome nao tem extensao — nenhum chamador precisa distinguir os dois. */
    public static String extensaoDe(String nome) {
        if (nome == null) {
            return "";
        }
        var ponto = nome.lastIndexOf('.');
        return ponto < 0 || ponto == nome.length() - 1
                ? ""
                : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    /** Nome do Pacote sugerido ao cliente: o do Video, com .zip no lugar da extensao. */
    public static String nomeDoPacotePara(String nomeDoVideo) {
        var ponto = nomeDoVideo.lastIndexOf('.');
        return (ponto < 0 ? nomeDoVideo : nomeDoVideo.substring(0, ponto)) + ".zip";
    }
}
