package br.com.fiapx.videos.core.entities;

/**
 * Identidade + endereco de e-mail do dono de um Video, ambos vindos do token no momento do
 * envio. Andam sempre juntos: a identidade e por quem se pergunta, o e-mail e para onde o
 * aviso de falha vai (CONTEXT.md).
 *
 * <p>O {@code sub} nao e validado como UUID de proposito: amarraria o dominio ao formato de
 * id do Keycloak, e o CONTEXT.md declara a identidade como string opaca.
 */
public record Dono(String sub, String email) {

    public Dono {
        sub = exigirNaoBranco(sub, "sub do dono");
        email = exigirNaoBranco(email, "e-mail do dono");
    }

    private static String exigirNaoBranco(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " é obrigatório");
        }
        return valor.trim();
    }
}
