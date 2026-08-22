package br.com.fiapx.videos.framework.web;

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
 * As seis situacoes da tabela de erros do contrato HTTP, num arquivo so — cada uma e tres
 * linhas, e espalha-las por seis arquivos esconderia a tabela em vez de mostra-la.
 *
 * <p>Falta uma setima: o <b>413</b> do corpo acima do teto. O Vert.x corta o corpo antes do
 * JAX-RS, entao ele nao passa por ExceptionMapper e nao sai como problem+json. E
 * inconsistencia assumida no ticket 008, nao bug a cacar.
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
