package br.com.fiapx.videos.framework.web;

/**
 * Corpo de erro em {@code application/problem+json} (RFC 9457).
 *
 * <p>Os nomes dos campos sao em ingles porque sao do padrao; os <b>valores</b> seguem em
 * portugues. A incoerencia de lingua fica confinada a este envelope.
 *
 * <p>{@code type} e fixo em {@code about:blank} — inventar uma URI de tipo que nao resolve e
 * pior que nao ter. Sem {@code instance}.
 */
public record ProblemDetail(String type, String title, int status, String detail) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public static ProblemDetail de(int status, String title, String detail) {
        return new ProblemDetail("about:blank", title, status, detail);
    }
}
