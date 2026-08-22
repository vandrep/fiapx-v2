package br.com.fiapx.videos.bdd;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.reactive.mutiny.Mutiny;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercita a borda pelo RestAssured e nunca chama use case ou gateway direto: o que o BDD
 * valida e comportamento observavel de fora.
 *
 * <p>As duas excecoes sao <b>montagem de cenario</b>, nao verificacao: por o Video em
 * CONCLUIDO (a transicao vem pela mensageria, que e o ticket 017) e apagar o objeto do
 * bucket — nao ha como esperar sete dias pela regra de ciclo de vida do MinIO.
 */
public class VideosSteps {

    private static final byte[] CONTEUDO_DO_UPLOAD = "conteudo de video para teste".getBytes();

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @Inject
    S3AsyncClient s3;

    @ConfigProperty(name = "fiapx.armazenamento.bucket-pacotes")
    String bucketPacotes;

    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    private String token;
    private Response resposta;
    private UUID idDoVideo;

    @Before
    public void limparEstadoEntreCenarios() {
        token = null;
        resposta = null;
        idDoVideo = null;
        sessionFactory
                .withTransaction(sessao -> sessao.createMutationQuery("delete from VideoEntity").executeUpdate())
                .await().indefinitely();
    }

    @Dado("que estou autenticado como {string}")
    public void queEstouAutenticadoComo(String usuario) {
        token = keycloak.getAccessToken(usuario);
    }

    @Quando("eu me autentico como {string}")
    public void euMeAutenticoComo(String usuario) {
        queEstouAutenticadoComo(usuario);
    }

    @Quando("eu envio o arquivo {string} com content-type {string}")
    public void euEnvioOArquivoComContentType(String nome, String contentType) {
        resposta = autenticado()
                .multiPart("arquivo", nome, CONTEUDO_DO_UPLOAD, contentType)
                .when().post("/videos");
    }

    @Quando("eu envio uma requisição multipart sem o campo arquivo")
    public void euEnvioMultipartSemOCampoArquivo() {
        resposta = autenticado()
                .multiPart("outro-campo", "irrelevante")
                .when().post("/videos");
    }

    @Dado("que enviei o arquivo {string}")
    public void queEnvieiOArquivo(String nome) {
        euEnvioOArquivoComContentType(nome, "video/mp4");
        resposta.then().statusCode(202);
        idDoVideo = UUID.fromString(resposta.jsonPath().getString("id"));
    }

    @Dado("que o usuário {string} enviou o arquivo {string}")
    public void queOUsuarioEnviouOArquivo(String usuario, String nome) {
        var meuToken = token;
        token = keycloak.getAccessToken(usuario);
        euEnvioOArquivoComContentType(nome, "video/mp4");
        resposta.then().statusCode(202);
        token = meuToken;
    }

    /**
     * Montagem de cenario: leva o Video a CONCLUIDO e poe o objeto no bucket. A transicao de
     * verdade chega por evento, que e o ticket 017.
     */
    @Dado("que a Extração do Vídeo concluiu com um Pacote de {int} bytes")
    public void queAExtracaoConcluiuComPacoteDe(int tamanho) {
        var chavePacote = idDoVideo + ".zip";
        s3.putObject(
                PutObjectRequest.builder().bucket(bucketPacotes).key(chavePacote).build(),
                AsyncRequestBody.fromByteBuffer(ByteBuffer.allocate(tamanho))).join();

        sessionFactory.withTransaction(sessao -> sessao.find(VideoEntity.class, idDoVideo)
                .invoke(entidade -> {
                    entidade.estado = EstadoVideo.CONCLUIDO;
                    entidade.finalizadoEm = Instant.now();
                    entidade.chavePacote = chavePacote;
                    entidade.quantidadeFrames = 1200;
                    entidade.tamanhoPacoteBytes = (long) tamanho;
                })).await().indefinitely();
    }

    @Dado("que o Pacote sumiu do armazenamento")
    public void queOPacoteSumiuDoArmazenamento() {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketPacotes)
                .key(idDoVideo + ".zip")
                .build()).join();
    }

    @Quando("eu consulto o Vídeo enviado")
    public void euConsultoOVideoEnviado() {
        resposta = autenticado().when().get("/videos/" + idDoVideo);
    }

    @Quando("eu consulto um Vídeo que não existe")
    public void euConsultoUmVideoQueNaoExiste() {
        resposta = autenticado().when().get("/videos/" + UUID.randomUUID());
    }

    @Quando("eu listo os meus Vídeos")
    public void euListoOsMeusVideos() {
        resposta = autenticado().when().get("/videos");
    }

    @Quando("eu listo os meus Vídeos no estado {string}")
    public void euListoOsMeusVideosNoEstado(String estado) {
        resposta = autenticado().queryParam("estado", estado).when().get("/videos");
    }

    @Quando("eu listo os meus Vídeos com página {int} e tamanho {int}")
    public void euListoOsMeusVideosComPaginaETamanho(int pagina, int tamanho) {
        resposta = autenticado()
                .queryParam("pagina", pagina)
                .queryParam("tamanho", tamanho)
                .when().get("/videos");
    }

    @Quando("eu listo os meus Vídeos sem autenticação")
    public void euListoOsMeusVideosSemAutenticacao() {
        resposta = given().when().get("/videos");
    }

    @Quando("eu baixo o Pacote do Vídeo")
    public void euBaixoOPacoteDoVideo() {
        resposta = autenticado().when().get("/videos/" + idDoVideo + "/pacote");
    }

    @Entao("a resposta tem status {int}")
    public void aRespostaTemStatus(int status) {
        assertEquals(status, resposta.statusCode(), () -> "corpo: " + resposta.asString());
    }

    @E("o cabeçalho {string} aponta para o Vídeo criado")
    public void oCabecalhoApontaParaOVideoCriado(String cabecalho) {
        var id = resposta.jsonPath().getString("id");
        assertNotNull(id);
        assertThat(resposta.header(cabecalho), containsString("/videos/" + id));
    }

    @E("o cabeçalho {string} contém {string}")
    public void oCabecalhoContem(String cabecalho, String trecho) {
        assertThat(resposta.header(cabecalho), containsString(trecho));
    }

    @E("o campo {string} da resposta é {string}")
    public void oCampoDaRespostaE(String campo, String valor) {
        assertEquals(valor, resposta.jsonPath().getString(campo));
    }

    @E("o campo {string} da resposta é nulo")
    public void oCampoDaRespostaENulo(String campo) {
        assertNull(resposta.jsonPath().get(campo));
    }

    @E("a resposta é um problem+json com título {string}")
    public void aRespostaEUmProblemJsonComTitulo(String titulo) {
        assertThat(resposta.contentType(), containsString("application/problem+json"));
        assertEquals("about:blank", resposta.jsonPath().getString("type"));
        assertEquals(titulo, resposta.jsonPath().getString("title"));
        assertEquals(resposta.statusCode(), resposta.jsonPath().getInt("status"));
        assertTrue(resposta.jsonPath().getString("detail") != null
                && !resposta.jsonPath().getString("detail").isBlank());
    }

    @E("a listagem tem {int} itens e total {int}")
    public void aListagemTemItensETotal(int itens, int total) {
        assertEquals(itens, resposta.jsonPath().getList("conteudo").size());
        assertEquals(total, resposta.jsonPath().getInt("total"));
    }

    @E("o corpo da resposta tem {int} bytes")
    public void oCorpoDaRespostaTemBytes(int tamanho) {
        var corpo = resposta.asByteArray();
        assertEquals(tamanho, corpo.length, () -> "content-type=" + resposta.contentType()
                + " corpo=" + new String(corpo).substring(0, Math.min(200, corpo.length)));
    }

    @Entao("o Vídeo continua em {string}")
    public void oVideoContinuaEm(String estado) {
        // O GET que descobre a ausencia nao grava nada: a tabela e o registro do que
        // aconteceu, nao um espelho do bucket.
        var persistido = sessionFactory
                .withSession(sessao -> sessao.find(VideoEntity.class, idDoVideo))
                .await().indefinitely();
        assertEquals(EstadoVideo.valueOf(estado), persistido.estado);
        assertNotNull(persistido.chavePacote);
    }

    private RequestSpecification autenticado() {
        return token == null ? given() : given().auth().oauth2(token);
    }
}
