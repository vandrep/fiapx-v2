package br.com.fiapx.notificacao.framework.web;

import br.com.fiapx.notificacao.interfaces.controllers.ItemController;
import br.com.fiapx.notificacao.interfaces.presenters.ItemPresenterAdapter;
import br.com.fiapx.notificacao.interfaces.presenters.view_model.ItemViewModel;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Tag(name = "Item")
@Path("/itens")
public class ItemResource {

    @Inject ItemController itemController;
    @Inject ItemPresenterAdapter itemPresenter;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> create(ItemController.ItemRequest itemRequest) {
        return Uni.createFrom().completionStage(itemController.criar(itemRequest))
                .map(id -> Response.status(Response.Status.CREATED).entity(id).build());
    }

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<ItemViewModel> read(@PathParam("id") Long id) {
        return Uni.createFrom().completionStage(itemController.buscar(id))
                .replaceWith(itemPresenter::viewModel);
    }
}
