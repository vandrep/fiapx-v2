package br.com.fiapx.extracao.interfaces.presenters;

import br.com.fiapx.extracao.core.interfaces.presenter.ItemPresenter;
import br.com.fiapx.extracao.core.interfaces.presenter.dto.ItemDTO;
import br.com.fiapx.extracao.interfaces.presenters.view_model.ItemViewModel;

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
