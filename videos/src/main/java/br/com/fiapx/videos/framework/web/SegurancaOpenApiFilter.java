package br.com.fiapx.videos.framework.web;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;

/**
 * Poe o botao <b>Authorize</b> no Swagger UI, com fluxo {@code password} apontando para o
 * Keycloak. Sem ele nao ha onde colar credencial nenhuma e a demo — que <i>e</i> o Swagger
 * UI — viraria curl.
 *
 * <p>Filtro de <b>runtime</b>, e nao anotacao {@code @SecurityScheme}, porque a URL do realm
 * precisa ser configuravel: em {@code @QuarkusTest} o Dev Services for Keycloak sorteia a
 * porta, entao uma URL literal ficaria errada em teste.
 */
@OpenApiFilter(OpenApiFilter.RunStage.RUN)
public class SegurancaOpenApiFilter implements OASFilter {

    private static final String NOME_DO_ESQUEMA = "keycloak";
    private static final String URL_DO_TOKEN = "fiapx.openapi.token-url";

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        var fluxoPassword = OASFactory.createOAuthFlow()
                .tokenUrl(ConfigProvider.getConfig().getValue(URL_DO_TOKEN, String.class));

        var esquema = OASFactory.createSecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("Login com o usuário de demonstração do realm. Requer direct access"
                        + " grants habilitado no client.")
                .flows(OASFactory.createOAuthFlows().password(fluxoPassword));

        var componentes = openAPI.getComponents() == null
                ? OASFactory.createComponents()
                : openAPI.getComponents();
        openAPI.setComponents(componentes.addSecurityScheme(NOME_DO_ESQUEMA, esquema));
        openAPI.addSecurityRequirement(OASFactory.createSecurityRequirement().addScheme(NOME_DO_ESQUEMA));
    }
}
