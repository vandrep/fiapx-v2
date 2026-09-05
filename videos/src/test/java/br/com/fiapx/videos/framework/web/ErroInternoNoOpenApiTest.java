package br.com.fiapx.videos.framework.web;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A tabela de erros do contrato HTTP termina em "qualquer outra: 500", e o
 * {@code ProblemDetailMappers.ErroInterno} a cumpre — mas quem le a API le o Swagger, nao o
 * mapper. Este teste julga o documento <b>gerado</b>, e nao as anotacoes do
 * {@code VideosResource}: e o documento que o avaliador ve (ticket 043).
 */
@QuarkusTest
class ErroInternoNoOpenApiTest {

    @ParameterizedTest(name = "{1} {0}")
    @CsvSource({
            "/videos,post",
            "/videos,get",
            "/videos/{id},get",
            "/videos/{id}/pacote,get"
    })
    void cadaOperacaoPublicaDeclaraOErroInterno(String caminho, String verbo) {
        var documento = JsonPath.from(given().accept("application/json")
                .when().get("/q/openapi")
                .then().statusCode(200)
                .extract().asString());

        var resposta = documento.getMap("paths.'" + caminho + "'." + verbo + ".responses.'500'");

        assertNotNull(resposta,
                "o contrato HTTP preve 500 em qualquer operacao; falta em " + verbo + " " + caminho);
    }
}
