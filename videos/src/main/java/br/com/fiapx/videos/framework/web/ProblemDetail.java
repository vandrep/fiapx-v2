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

    /**
     * O {@code Retry-After} dos dois {@code 503} do envio, o da recusa por capacidade e o do
     * armazenamento indisponivel (ticket 108). E sugestao, nao medida: a ordem de grandeza de um
     * envio em andamento terminar, ou das repeticoes do MinIO do ADR 0001 (4 s) se esgotarem.
     */
    public static final int ESPERA_SUGERIDA_EM_SEGUNDOS = 5;

    public static ProblemDetail de(int status, String title, String detail) {
        return new ProblemDetail("about:blank", title, status, detail);
    }

    /** O {@code detail} de um {@code 503} do envio, com a mesma espera que vai no {@code Retry-After}. */
    public static String comEsperaSugerida(String motivo) {
        return motivo + "; tente de novo em " + ESPERA_SUGERIDA_EM_SEGUNDOS + " s";
    }
}
