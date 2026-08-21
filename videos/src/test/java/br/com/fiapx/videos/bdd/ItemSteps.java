package br.com.fiapx.videos.bdd;

import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Steps do modulo `videos`, escritos contra a borda HTTP (mesma abordagem dos testes de
 * integracao em ItemResourceTest): BDD aqui valida comportamento observavel de fora, nao
 * detalhe de implementacao do core.
 */
public class ItemSteps {

    private final Map<String, String> idsPorNome = new HashMap<>();
    private Response ultimaResposta;

    @Quando("eu cadastro um item com o nome {string}")
    public void euCadastroUmItemComONome(String nome) {
        var id = given()
                .contentType("application/json")
                .body("{\"nome\": \"" + nome + "\"}")
                .when().post("/itens")
                .then().statusCode(201)
                .extract().response().asString();
        idsPorNome.put(nome, id);
    }

    @Entao("a busca pelo item cadastrado retorna o nome {string}")
    public void aBuscaPeloItemCadastradoRetornaONome(String nome) {
        var id = idsPorNome.get(nome);
        given()
                .when().get("/itens/" + id)
                .then().statusCode(200)
                .body("nome", equalTo(nome));
    }

    @Quando("eu busco um item com um id que nao existe")
    public void euBuscoUmItemComUmIdQueNaoExiste() {
        ultimaResposta = given().when().get("/itens/999999999");
    }

    @Entao("a busca retorna que o item nao foi encontrado")
    public void aBuscaRetornaQueOItemNaoFoiEncontrado() {
        ultimaResposta.then().statusCode(404);
    }
}
