package br.com.fiapx.videos.core.interfaces.presenter.dto;

import java.util.List;

/**
 * Uma fatia da listagem. O {@code total} nao e {@code conteudo.size()}: e a contagem inteira,
 * que o adapter resolve com uma segunda consulta.
 */
public record Pagina<T>(List<T> conteudo, int pagina, int tamanho, long total) {
}
