package br.com.fiapx.extracao.framework.web;

import br.com.fiapx.extracao.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.extracao.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.extracao.core.usecases.item.BuscarItemUseCase;
import br.com.fiapx.extracao.core.usecases.item.CriarItemUseCase;
import br.com.fiapx.extracao.interfaces.controllers.ItemController;
import br.com.fiapx.extracao.interfaces.presenters.ItemPresenterAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class ExtracaoConfiguration {

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
