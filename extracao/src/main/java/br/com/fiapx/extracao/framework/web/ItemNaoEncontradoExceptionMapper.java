package br.com.fiapx.extracao.framework.web;

import br.com.fiapx.extracao.core.exceptions.ItemNaoEncontradoException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class ItemNaoEncontradoExceptionMapper implements ExceptionMapper<ItemNaoEncontradoException> {
    @Override
    public Response toResponse(ItemNaoEncontradoException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", exception.getMessage()))
                .build();
    }
}
