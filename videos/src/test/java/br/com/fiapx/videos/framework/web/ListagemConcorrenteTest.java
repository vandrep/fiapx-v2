package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A listagem sob rajada, contra o Postgres de verdade. Existe por causa do ticket 045: a
 * busca da pagina e a contagem saiam do mesmo {@code Uni.combine().all()}, isto e, duas
 * consultas subscritas ao mesmo tempo na <b>mesma</b> sessao reativa — o defeito que o
 * ticket 017 ja tinha visto corromper a sessao em outro fluxo.
 *
 * <p><b>Este teste nao reproduz o defeito, e nao promete reproduzir.</b> A corrida e
 * <i>dentro</i> de uma requisicao — cada uma tem sessao propria —, entao a rajada nao
 * aumenta a chance de vê-la; com o {@code Uni.combine()} original ele passava. Ele existe
 * como validacao da correcao sob carga real: <b>toda</b> resposta 200, com conteudo, total,
 * pagina, tamanho e ordem corretos, com o pool e o event loop disputados. A cerca contra a
 * volta da forma concorrente e o
 * {@code br.com.fiapx.videos.framework.db.ListagemSerializadaTest}.
 */
@QuarkusTest
class ListagemConcorrenteTest {

    private static final int VIDEOS = 5;
    private static final int TAMANHO_DA_PAGINA = 3;
    private static final int REQUISICOES_SIMULTANEAS = 40;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    private String token;

    @BeforeEach
    void prepararOsVideosDoDono() {
        sessionFactory
                .withTransaction(sessao -> sessao.createMutationQuery("delete from VideoEntity").executeUpdate())
                .await().indefinitely();
        token = keycloak.getAccessToken("demo");
        IntStream.range(0, VIDEOS).forEach(indice -> given().auth().oauth2(token)
                .multiPart("arquivo", "rajada-" + indice + ".mp4", "conteudo".getBytes(), "video/mp4")
                .when().post("/videos")
                .then().statusCode(202));
    }

    @Test
    void requisicoesSimultaneasDevolvemPaginaETotalCorretos() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(REQUISICOES_SIMULTANEAS)) {
            var respostas = IntStream.range(0, REQUISICOES_SIMULTANEAS)
                    .mapToObj(indice -> CompletableFuture.supplyAsync(this::listarPrimeiraPagina, executor))
                    .toList();

            for (CompletableFuture<Response> pendente : respostas) {
                var resposta = pendente.get();
                assertEquals(200, resposta.statusCode(), () -> "corpo: " + resposta.asString());
                assertEquals(TAMANHO_DA_PAGINA, resposta.jsonPath().getList("conteudo").size());
                assertEquals(VIDEOS, resposta.jsonPath().getInt("total"));
                assertEquals(TAMANHO_DA_PAGINA, resposta.jsonPath().getInt("tamanho"));
                assertEquals(0, resposta.jsonPath().getInt("pagina"));
                assertEquals(nomesEmOrdemDeRecebimento(), resposta.jsonPath().getList("conteudo.nome"));
            }
        }
    }

    private Response listarPrimeiraPagina() {
        return given().auth().oauth2(token)
                .queryParam("pagina", 0)
                .queryParam("tamanho", TAMANHO_DA_PAGINA)
                .when().get("/videos");
    }

    /** Os enviados por ultimo vem primeiro: a ordenacao e por recebimento decrescente. */
    private static List<String> nomesEmOrdemDeRecebimento() {
        return IntStream.range(0, TAMANHO_DA_PAGINA)
                .mapToObj(indice -> "rajada-" + (VIDEOS - 1 - indice) + ".mp4")
                .toList();
    }
}
