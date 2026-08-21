package br.com.fiapx.notificacao.interfaces.presenters;

import br.com.fiapx.notificacao.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.notificacao.core.interfaces.presenter.dto.ItemDTO;
import br.com.fiapx.notificacao.interfaces.presenters.view_model.ItemViewModel;

public class ItemPresenterAdapter implements ItemPresenter {

    private ItemViewModel itemViewModel;

    @Override
    public void present(ItemDTO itemDTO) {
        this.itemViewModel = new ItemViewModel(itemDTO.id(), itemDTO.nome());
    }

    public ItemViewModel viewModel() {
        return itemViewModel;
    }
}
