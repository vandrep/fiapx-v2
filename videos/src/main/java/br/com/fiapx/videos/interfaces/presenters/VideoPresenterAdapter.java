package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;

public class VideoPresenterAdapter implements VideoPresenter {

    private VideoViewModel viewModel;

    @Override
    public void present(VideoDTO videoDTO) {
        this.viewModel = RepresentacaoDeVideo.de(videoDTO);
    }

    public VideoViewModel viewModel() {
        return viewModel;
    }
}
