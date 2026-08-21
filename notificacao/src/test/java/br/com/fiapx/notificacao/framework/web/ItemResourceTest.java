package br.com.fiapx.notificacao.framework.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ItemResourceTest {

    @Test
    void deveCriarEBuscarUmItem() {
        var id = given()
                .contentType("application/json")
                .body("""
                        {"nome": "Chave de fenda"}
                        """)
                .when().post("/itens")
                .then()
                .statusCode(201)
                .body(notNullValue())
                .extract().response().asString();

        given()
                .when().get("/itens/" + id)
                .then()
                .statusCode(200)
                .body("nome", equalTo("Chave de fenda"));
    }
}
