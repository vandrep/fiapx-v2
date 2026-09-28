package br.com.fiapx.videos.framework.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A recusa do ticket 108 vista de fora: {@code 503} com {@code Retry-After} <b>antes</b> de o
 * corpo ser lido. A conta em si esta em {@code CapacidadeDoEnvioTest}; aqui o que se julga e o
 * ponto em que ela roda.
 *
 * <p>Os envios que ocupam as vagas sao sockets crus, e nao RestAssured: um cliente HTTP manda o
 * corpo inteiro e so entao le a resposta, e o que prova "antes do corpo" e justamente a resposta
 * chegar com o corpo pela metade. Pelo mesmo motivo o envio recusado tambem fica pela metade.
 */
@QuarkusTest
class RecusaPorCapacidadeTest {

    private static final int MB = 1024 * 1024;
    private static final String FRONTEIRA = "fronteira-do-ticket-108";
    private static final Duration PRAZO = Duration.ofSeconds(10);

    private final KeycloakTestClient keycloak = new KeycloakTestClient();
    private final List<Socket> abertos = new ArrayList<>();

    @TestHTTPResource("/videos")
    URL videos;

    @ConfigProperty(name = "fiapx.borda.teto-de-envios-simultaneos")
    int teto;

    @ConfigProperty(name = "quarkus.http.body.uploads-directory")
    Path diretorioDeUploads;

    @AfterEach
    void fecharOsEnviosPelaMetade() throws IOException {
        for (Socket socket : abertos) {
            socket.close();
        }
        abertos.clear();
    }

    @Test
    void envioAcimaDoTetoRecebe503SemQueOCorpoChegueAoVolume() throws Exception {
        var token = keycloak.getAccessToken("demo");
        var arquivosAntes = arquivosNoVolume();
        for (int vaga = 0; vaga < teto; vaga++) {
            envioPelaMetade(token, 5 * MB);
        }
        aguardar(() -> arquivosNoVolume() >= arquivosAntes + teto,
                "os envios que ocupam as vagas tinham de estar gravando no volume");
        var arquivosComAsVagasOcupadas = arquivosNoVolume();

        var recusado = envioPelaMetade(token, 5 * MB);
        var resposta = lerResposta(recusado);

        assertEquals(503, resposta.status(), "com o corpo pela metade, a resposta tinha de ser a recusa");
        assertTrue(resposta.cabecalhos().containsKey("retry-after"), "503 sem Retry-After: " + resposta.cabecalhos());
        assertEquals(ProblemDetail.MEDIA_TYPE, resposta.cabecalhos().get("content-type"));
        assertEquals(arquivosComAsVagasOcupadas, arquivosNoVolume(),
                "o envio recusado chegou ao volume de uploads");
        assertEquals(503, lerResposta(envioPelaMetade(null, 5 * MB)).status(),
                "a recusa vem antes da autenticacao: sem vaga, o envio sem token tambem e 503, e nao 401");
    }

    @Test
    void envioQueTerminaOuDesisteDevolveAVaga() throws Exception {
        var token = keycloak.getAccessToken("demo");
        for (int vaga = 0; vaga < teto; vaga++) {
            envioPelaMetade(token, 5 * MB);
        }
        fecharOsEnviosPelaMetade();

        for (int envio = 0; envio <= teto; envio++) {
            aguardar(() -> enviarPequeno(token) == 202,
                    "o envio " + envio + " nao foi aceito: vaga de conexao fechada ou de envio terminado nao voltou");
        }
    }

    @Test
    void tamanhoDeclaradoMaiorQueOEspacoLivreRecebe503() {
        QuarkusMock.installMockForType(new VolumeDeUploads() {
            @Override
            public long espacoLivre() {
                return MB;
            }
        }, VolumeDeUploads.class);

        given().auth().oauth2(keycloak.getAccessToken("demo"))
                .multiPart("arquivo", "grande.mp4", new byte[2 * MB], "video/mp4")
                .when().post("/videos")
                .then()
                .statusCode(503)
                .header("Retry-After", notNullValue())
                .contentType(ProblemDetail.MEDIA_TYPE)
                .body("status", is(503))
                .body("title", is("Capacidade esgotada"));
    }

    /** A recusa por capacidade nao engole o 413: corpo declarado acima do teto continua com ele. */
    @Test
    void tamanhoDeclaradoAcimaDoTetoDoCorpoContinua413() throws Exception {
        QuarkusMock.installMockForType(new VolumeDeUploads() {
            @Override
            public long espacoLivre() {
                return MB;
            }
        }, VolumeDeUploads.class);

        var socket = envioPelaMetade(keycloak.getAccessToken("demo"), 300L * MB);

        assertEquals(413, lerResposta(socket).status());
    }

    private int enviarPequeno(String token) {
        return given().auth().oauth2(token)
                .multiPart("arquivo", "vaga.mp4", "conteudo".getBytes(), "video/mp4")
                .when().post("/videos")
                .statusCode();
    }

    /**
     * Cabecalhos, o inicio do multipart e 64 KB do arquivo; o resto do corpo declarado nunca vai.
     * Sem {@code token}, sai sem {@code Authorization}.
     */
    private Socket envioPelaMetade(String token, long tamanhoDeclarado) throws IOException {
        var socket = new Socket(videos.getHost(), videos.getPort());
        socket.setSoTimeout((int) PRAZO.toMillis());
        abertos.add(socket);
        var inicioDoMultipart = ("--" + FRONTEIRA + "\r\n"
                + "Content-Disposition: form-data; name=\"arquivo\"; filename=\"pela-metade.mp4\"\r\n"
                + "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        var cabecalhos = ("POST " + videos.getPath() + " HTTP/1.1\r\n"
                + "Host: " + videos.getHost() + ":" + videos.getPort() + "\r\n"
                + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                + "Content-Type: multipart/form-data; boundary=" + FRONTEIRA + "\r\n"
                + "Content-Length: " + tamanhoDeclarado + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        OutputStream saida = socket.getOutputStream();
        saida.write(cabecalhos);
        saida.write(inicioDoMultipart);
        saida.write(new byte[64 * 1024]);
        saida.flush();
        return socket;
    }

    private static Resposta lerResposta(Socket socket) throws IOException {
        var leitor = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var linhaDeStatus = leitor.readLine();
        if (linhaDeStatus == null) {
            fail("a conexao fechou sem resposta");
        }
        var cabecalhos = new LinkedHashMap<String, String>();
        for (var linha = leitor.readLine(); linha != null && !linha.isEmpty(); linha = leitor.readLine()) {
            var separador = linha.indexOf(':');
            cabecalhos.put(linha.substring(0, separador).trim().toLowerCase(Locale.ROOT), linha.substring(separador + 1).trim());
        }
        return new Resposta(Integer.parseInt(linhaDeStatus.split(" ")[1]), cabecalhos);
    }

    private long arquivosNoVolume() throws IOException {
        if (!Files.isDirectory(diretorioDeUploads)) {
            return 0;
        }
        try (Stream<Path> arquivos = Files.list(diretorioDeUploads)) {
            return arquivos.filter(Files::isRegularFile).count();
        }
    }

    private static void aguardar(Condicao condicao, String mensagem) throws Exception {
        var limite = System.nanoTime() + PRAZO.toNanos();
        while (!condicao.vale()) {
            if (System.nanoTime() > limite) {
                fail(mensagem);
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    private interface Condicao {
        boolean vale() throws Exception;
    }

    private record Resposta(int status, Map<String, String> cabecalhos) {
    }
}
