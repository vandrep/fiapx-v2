package br.com.fiapx.videos.core.interfaces.gateway;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A guarda de propriedade e <b>estrutural</b>: nao existe {@code buscarPorId(UUID)} aqui, so
 * {@code buscarPorIdEDono}. A regra nao e "verifique o dono", e "nao ha como pedir um Video
 * sem dizer de quem" — impossivel de furar por esquecimento (ticket 009).
 *
 * <p>So {@code dono.sub()} participa dos predicados; o e-mail e carga, viaja junto porque o
 * evento VideoFalhou precisa dele.
 */
public interface VideoGateway {

    CompletableFuture<Void> adicionar(Video video);

    CompletableFuture<Optional<Video>> buscarPorIdEDono(UUID id, Dono dono);

    /**
     * Ordenacao fixa por {@code recebidoEm} decrescente — o contrato HTTP nao tem parametro
     * de ordenacao, e o indice do banco e desse formato.
     *
     * @param estado filtro opcional; vazio lista os quatro estados
     */
    CompletableFuture<Pagina<Video>> listarPorDono(Dono dono,
                                                   Optional<EstadoVideo> estado,
                                                   int pagina,
                                                   int tamanho);
}
