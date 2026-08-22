package br.com.fiapx.videos.core.interfaces.presenter;

import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

public interface VideosPaginadosPresenter {

    void present(Pagina<VideoDTO> pagina);
}
