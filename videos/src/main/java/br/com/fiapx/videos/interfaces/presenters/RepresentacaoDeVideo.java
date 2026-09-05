package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;

/**
 * A unica conversao de Video para a representacao publica. O corpo do envio, o do GET
 * individual e cada item da listagem saem daqui: o contrato publica uma representacao so, e
 * duas copias em sincronia manual sao um jeito de ela divergir (ticket 047).
 *
 * <p>Funcao pura numa classe propria, e nao um metodo do presenter individual: o presenter
 * guarda o resultado de <b>uma</b> requisicao em campo mutavel, e a listagem converte em laco.
 */
final class RepresentacaoDeVideo {

    private RepresentacaoDeVideo() {
    }

    static VideoViewModel de(VideoDTO videoDTO) {
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
