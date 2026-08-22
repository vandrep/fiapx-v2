package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;

public class VideoPresenterAdapter implements VideoPresenter {

    private VideoViewModel viewModel;

    @Override
    public void present(VideoDTO videoDTO) {
        this.viewModel = new VideoViewModel(
                videoDTO.id(),
                videoDTO.nome(),
                videoDTO.estado(),
                videoDTO.tamanhoBytes(),
                videoDTO.recebidoEm(),
                videoDTO.finalizadoEm(),
                videoDTO.motivo());
    }

    public VideoViewModel viewModel() {
        return viewModel;
    }
}
