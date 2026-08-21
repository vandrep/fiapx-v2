package br.com.fiapx.notificacao.framework.web;

import br.com.fiapx.notificacao.core.interfaces.gateway.ItemGateway;
import br.com.fiapx.notificacao.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.notificacao.core.usecases.item.BuscarItemUseCase;
import br.com.fiapx.notificacao.core.usecases.item.CriarItemUseCase;
import br.com.fiapx.notificacao.interfaces.controllers.ItemController;
import br.com.fiapx.notificacao.interfaces.presenters.ItemPresenterAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class NotificacaoConfiguration {

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
