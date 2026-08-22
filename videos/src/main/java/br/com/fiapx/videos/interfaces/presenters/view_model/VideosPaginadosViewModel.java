package br.com.fiapx.videos.interfaces.presenters.view_model;

import java.util.List;

/** Sem cabecalhos {@code Link}: a paginacao inteira cabe no corpo. */
public record VideosPaginadosViewModel(List<VideoViewModel> conteudo, int pagina, int tamanho, long total) {
}
