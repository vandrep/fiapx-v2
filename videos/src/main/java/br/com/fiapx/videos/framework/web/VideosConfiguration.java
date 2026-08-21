package br.com.fiapx.videos.framework.web;

import br.com.fiapx.videos.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.videos.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.videos.core.usecases.item.BuscarItemUseCase;
import br.com.fiapx.videos.core.usecases.item.CriarItemUseCase;
import br.com.fiapx.videos.interfaces.controllers.ItemController;
import br.com.fiapx.videos.interfaces.presenters.ItemPresenterAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class VideosConfiguration {

    @Produces
    ItemController itemController(ItemGateway itemGateway, ItemPresenter itemPresenter) {
        return new ItemController(
                new CriarItemUseCase(itemGateway),
                new BuscarItemUseCase(itemGateway, itemPresenter));
    }

    @Produces
    @RequestScoped
    ItemPresenterAdapter itemPresenter() {
        return new ItemPresenterAdapter();
    }
}
