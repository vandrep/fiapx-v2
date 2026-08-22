package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.interfaces.presenter.VideosPaginadosPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideosPaginadosViewModel;

public class VideosPaginadosPresenterAdapter implements VideosPaginadosPresenter {

    private VideosPaginadosViewModel viewModel;

    @Override
    public void present(Pagina<VideoDTO> pagina) {
        this.viewModel = new VideosPaginadosViewModel(
                pagina.conteudo().stream().map(VideosPaginadosPresenterAdapter::paraViewModel).toList(),
                pagina.pagina(),
                pagina.tamanho(),
                pagina.total());
    }

    public VideosPaginadosViewModel viewModel() {
        return viewModel;
    }

    private static VideoViewModel paraViewModel(VideoDTO videoDTO) {
        return new VideoViewModel(
                videoDTO.id(),
                videoDTO.nome(),
                videoDTO.estado(),
                videoDTO.tamanhoBytes(),
                videoDTO.recebidoEm(),
                videoDTO.finalizadoEm(),
                videoDTO.motivo());
    }
}
