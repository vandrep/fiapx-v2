package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideosPaginadosViewModel;

public class VideosPaginadosPresenterAdapter implements VideosPaginadosPresenter {

    private VideosPaginadosViewModel viewModel;

    @Override
    public void present(Pagina<VideoDTO> pagina) {
        this.viewModel = new VideosPaginadosViewModel(
                pagina.conteudo().stream().map(RepresentacaoDeVideo::de).toList(),
                pagina.pagina(),
                pagina.tamanho(),
                pagina.total());
    }

    public VideosPaginadosViewModel viewModel() {
        return viewModel;
    }
}
