package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.exceptions.ArquivoAusenteException;
import br.com.fiapx.videos.core.usecases.video.BaixarPacoteUseCase;
import br.com.fiapx.videos.interfaces.controllers.VideosController;
import br.com.fiapx.videos.interfaces.presenters.VideoPresenterAdapter;
import br.com.fiapx.videos.interfaces.presenters.VideosPaginadosPresenterAdapter;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideosPaginadosViewModel;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestMulti;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A unica borda publica do sistema. Sem interface web, este recurso <b>e</b> o produto: e o
 * que a banca ve no Swagger UI e o que o script de smoke exercita.
 *
 * <p><b>O dono vem do token, nunca do request.</b> Nao existe parametro de usuario em lugar
 * nenhum deste recurso. E vem de {@code getSubject()}, nao de {@code getName()}: o segundo e
 * o {@code upn}/{@code preferred_username}, e usa-lo como dono seria um bug silencioso de
 * autorizacao (ticket 004).
 *
 * <p>Anotacoes OpenAPI so aqui, e so as que o gerador nao acerta sozinho: o caminho feliz
 * ele deduz dos tipos, mas os status de erro nao.
 */
@Tag(name = "Videos", description = "Envio, acompanhamento e download dos Vídeos do usuário autenticado")
@Path("/videos")
@Authenticated
public class VideosResource {

    @Inject
    JsonWebToken token;

    @Inject
    VideosController videosController;

    @Inject
    VideoPresenterAdapter videoPresenter;

    @Inject
    VideosPaginadosPresenterAdapter videosPaginadosPresenter;

    @WithTransaction
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Envia um Vídeo para extração de frames",
            description = "Responde 202: o Vídeo foi armazenado e enfileirado, e a Extração ainda"
                    + " não começou. Acompanhe pelo Location. Formatos aceitos: mp4, avi, mov, mkv,"
                    + " webm, até 200 MB. Vídeos com mais de 20 minutos são recusados depois do 202,"
                    + " por e-mail — esta borda não decodifica o arquivo.")
    @APIResponse(responseCode = "202", description = "Vídeo recebido, em RECEBIDO")
    @APIResponse(responseCode = "400", description = "Campo arquivo ausente ou vazio")
    @APIResponse(responseCode = "413", description = "Corpo acima de 200 MB (resposta do servidor HTTP, não problem+json)")
    @APIResponse(responseCode = "415", description = "Content-type ou extensão fora da lista aceita")
    public Uni<Response> enviar(@RestForm("arquivo") FileUpload arquivo) {
        if (arquivo == null || arquivo.size() <= 0) {
            throw new ArquivoAusenteException("O campo 'arquivo' é obrigatório e não pode estar vazio");
        }
        var requisicao = new VideosController.EnvioRequest(
                arquivo.fileName(),
                arquivo.contentType(),
                arquivo.size(),
                arquivo.uploadedFile(),
                sub(),
                email());
        return doController(() -> videosController.enviar(requisicao))
                .map(id -> Response.accepted(videoPresenter.viewModel())
                        .location(java.net.URI.create("/videos/" + id))
                        .build());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Lista os Vídeos do usuário",
            description = "Ordenação fixa por recebidoEm decrescente; não há parâmetro de ordenação.")
    @APIResponse(responseCode = "200", description = "Página de Vídeos do usuário")
    public Uni<VideosPaginadosViewModel> listar(@QueryParam("estado") EstadoVideo estado,
                                                @QueryParam("pagina") @jakarta.ws.rs.DefaultValue("0") int pagina,
                                                @QueryParam("tamanho") @jakarta.ws.rs.DefaultValue("20") int tamanho) {
        var requisicao = new VideosController.ListagemRequest(sub(), email(), estado, pagina, tamanho);
        return doController(() -> videosController.listar(requisicao))
                .replaceWith(videosPaginadosPresenter::viewModel);
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Consulta um Vídeo do usuário")
    @APIResponse(responseCode = "200", description = "O Vídeo")
    @APIResponse(responseCode = "404", description = "O Vídeo não existe, ou não é seu")
    public Uni<VideoViewModel> consultar(@PathParam("id") UUID id) {
        return doController(() -> videosController.consultar(id, sub(), email()))
                .replaceWith(videoPresenter::viewModel);
    }

    /**
     * Stream pelo proprio `videos`, e nao redirect para presigned URL: a URL assinada e um
     * <i>bearer token</i> — a posse do Video seria conferida uma vez, na emissao —, e no
     * Compose o host entra na assinatura (ticket 008).
     */
    @GET
    @Path("{id}/pacote")
    @Produces("application/zip")
    @Operation(summary = "Baixa o Pacote de frames de um Vídeo CONCLUIDO",
            description = "O Pacote fica disponível por 7 dias após a conclusão da Extração.")
    @APIResponse(responseCode = "200", description = "O Pacote .zip, em streaming")
    @APIResponse(responseCode = "404", description = "O Vídeo não existe, ou não é seu")
    @APIResponse(responseCode = "409", description = "Ainda não: o Vídeo não está CONCLUIDO")
    @APIResponse(responseCode = "410", description = "Não mais: o Pacote expirou (7 dias)")
    public RestMulti<byte[]> baixarPacote(@PathParam("id") UUID id) {
        return RestMulti.fromUniResponse(
                doController(() -> videosController.baixarPacote(id, sub(), email())),
                pacote -> Multi.createFrom().publisher(pacote.conteudo()).map(VideosResource::bytes),
                VideosResource::cabecalhosDoPacote);
    }

    private static Map<String, List<String>> cabecalhosDoPacote(BaixarPacoteUseCase.Pacote pacote) {
        var cabecalhos = new LinkedHashMap<String, List<String>>();
        cabecalhos.put(HttpHeaders.CONTENT_DISPOSITION,
                List.of("attachment; filename=\"" + pacote.nomeSugerido() + "\""));
        if (pacote.tamanhoBytes() != null) {
            cabecalhos.put(HttpHeaders.CONTENT_LENGTH, List.of(String.valueOf(pacote.tamanhoBytes())));
        }
        return cabecalhos;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var copia = new byte[buffer.remaining()];
        buffer.get(copia);
        return copia;
    }

    /**
     * Ponte CompletableFuture -> Uni, desembrulhando o CompletionException que as cadeias
     * assincronas colocam por cima da excecao de dominio — sem isso, toda falha do core
     * cairia no mapper de 500 em vez do seu.
     */
    private static <T> Uni<T> doController(Supplier<java.util.concurrent.CompletableFuture<T>> chamada) {
        return Uni.createFrom().completionStage(chamada)
                .onFailure(CompletionException.class)
                .transform(falha -> falha.getCause() == null ? falha : falha.getCause());
    }

    /** O dono do Video. {@code getName()} viria do upn e seria outro campo (ticket 004). */
    private String sub() {
        return token.getSubject();
    }

    /** Carga, nao identidade: o evento VideoFalhou precisa dele para o `notificacao`. */
    private String email() {
        return token.getClaim("email");
    }
}
