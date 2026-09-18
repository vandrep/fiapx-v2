package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.exceptions.ArmazenamentoIndisponivelException;
import br.com.fiapx.videos.core.exceptions.ArquivoAusenteException;
import br.com.fiapx.videos.core.exceptions.FormatoNaoSuportadoException;
import br.com.fiapx.videos.core.exceptions.PacoteExpiradoException;
import br.com.fiapx.videos.core.exceptions.PacoteIndisponivelException;
import br.com.fiapx.videos.core.exceptions.VideoNaoEncontradoException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * As situacoes da tabela de erros do contrato HTTP que passam pelo JAX-RS, num arquivo so —
 * cada uma e tres linhas, e espalha-las por varios arquivos esconderia a tabela em vez de
 * mostra-la.
 *
 * <p>Faltam duas, e as duas acontecem antes do JAX-RS. O <b>413</b> do corpo acima do teto: o
 * Vert.x corta o corpo, entao ele nao passa por ExceptionMapper e nao sai como problem+json — e
 * inconsistencia assumida no ticket 008, nao bug a cacar. E o <b>503</b> da recusa por
 * capacidade, que sai de {@link RecusaPorCapacidade} antes de o corpo ser lido; esse escreve o
 * problem+json por conta propria (ticket 108).
 */
public final class ProblemDetailMappers {

    private ProblemDetailMappers() {
    }

    private static Response resposta(Response.Status status, String title, String detail) {
        return Response.status(status)
                .type(ProblemDetail.MEDIA_TYPE)
                .entity(ProblemDetail.de(status.getStatusCode(), title, detail))
                .build();
    }

    @Provider
    public static class VideoNaoEncontrado implements ExceptionMapper<VideoNaoEncontradoException> {
        @Override
        public Response toResponse(VideoNaoEncontradoException exception) {
            return resposta(Response.Status.NOT_FOUND, "Video nao encontrado", exception.getMessage());
        }
    }

    @Provider
    public static class PacoteIndisponivel implements ExceptionMapper<PacoteIndisponivelException> {
        @Override
        public Response toResponse(PacoteIndisponivelException exception) {
            return resposta(Response.Status.CONFLICT, "Pacote indisponivel", exception.getMessage());
        }
    }

    @Provider
    public static class PacoteExpirado implements ExceptionMapper<PacoteExpiradoException> {
        @Override
        public Response toResponse(PacoteExpiradoException exception) {
            return resposta(Response.Status.GONE, "Pacote expirado", exception.getMessage());
        }
    }

    @Provider
    public static class FormatoNaoSuportado implements ExceptionMapper<FormatoNaoSuportadoException> {
        @Override
        public Response toResponse(FormatoNaoSuportadoException exception) {
            return resposta(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Formato nao suportado", exception.getMessage());
        }
    }

    @Provider
    public static class ArquivoAusente implements ExceptionMapper<ArquivoAusenteException> {
        @Override
        public Response toResponse(ArquivoAusenteException exception) {
            return resposta(Response.Status.BAD_REQUEST, "Requisicao invalida", exception.getMessage());
        }
    }

    /**
     * O MinIO recusou a primeira escrita do envio. A causa vai para o log, porque o
     * {@code detail} nao a carrega e sem ela um MinIO fora do ar seria so uma sequencia de 503.
     */
    @Provider
    public static class ArmazenamentoIndisponivel implements ExceptionMapper<ArmazenamentoIndisponivelException> {

        private static final Logger LOG = Logger.getLogger(ArmazenamentoIndisponivel.class);

        @Override
        public Response toResponse(ArmazenamentoIndisponivelException exception) {
            LOG.warn(exception.getMessage(), exception.getCause());
            return Response.fromResponse(resposta(Response.Status.SERVICE_UNAVAILABLE, "Armazenamento indisponivel",
                            ProblemDetail.comEsperaSugerida("Não foi possível armazenar o Vídeo agora, e ele não foi aceito")))
                    .header("Retry-After", ProblemDetail.ESPERA_SUGERIDA_EM_SEGUNDOS)
                    .build();
        }
    }

    /**
     * O "qualquer outra" da tabela. Nao intercepta 401/403: o JAX-RS escolhe o mapper mais
     * especifico, e as excecoes de seguranca ja tem os seus.
     */
    @Provider
    public static class ErroInterno implements ExceptionMapper<Throwable> {

        private static final Logger LOG = Logger.getLogger(ErroInterno.class);

        @Override
        public Response toResponse(Throwable throwable) {
            if (throwable instanceof WebApplicationException falhaHttp) {
                return falhaHttp.getResponse();
            }
            LOG.error("Falha nao tratada na borda HTTP", throwable);
            return resposta(Response.Status.INTERNAL_SERVER_ERROR, "Erro interno",
                    "Não foi possível concluir a requisição");
        }
    }
}
