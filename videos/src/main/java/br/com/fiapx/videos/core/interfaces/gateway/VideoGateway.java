package br.com.fiapx.videos.core.interfaces.gateway;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A borda HTTP sempre usa {@link #buscarPorIdEDono}; o caminho de mensageria, que nao recebe
 * Dono, usa {@link #buscarPorId}. O teste arquitetural proibe Resource e controller HTTP de
 * usarem a busca sem posse (ticket 031).
 *
 * <p>So {@code dono.sub()} participa dos predicados; o e-mail e carga, viaja junto porque o
 * evento VideoFalhou precisa dele.
 */
public interface VideoGateway {

    CompletableFuture<Void> adicionar(Video video);

    CompletableFuture<Optional<Video>> buscarPorIdEDono(UUID id, Dono dono);

    /** Busca interna exclusiva do caminho de mensageria, que nao carrega o Dono. */
    CompletableFuture<Optional<Video>> buscarPorId(UUID id);

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

    /**
     * A Extracao comecou. {@code true} so quando esta chamada de fato tirou a linha de
     * RECEBIDO — reentrega fora de ordem devolve {@code false} e o consumidor da ack do
     * mesmo jeito (ADR 0002). Os predecessores aceitos no {@code WHERE} vem de
     * {@link EstadoVideo#predecessores()}, nao de literais aqui: o grafo continua declarado
     * uma vez so. O {@code iniciadaEm} so e gravado junto da transicao, entao a reentrega nao
     * reescreve o da primeira tentativa (ticket 106).
     */
    CompletableFuture<Boolean> marcarIniciada(UUID id, Instant iniciadaEm);

    /**
     * Mesma guarda de {@link #marcarIniciada}, agora para CONCLUIDO — e saindo de RECEBIDO
     * <b>ou</b> de PROCESSANDO, porque a {@code ExtracaoConcluida} pode chegar antes da
     * {@code ExtracaoIniciada} (ticket 027, ADR 0002).
     */
    CompletableFuture<Boolean> marcarConcluida(UUID id, ResultadoExtracao resultado);

    /**
     * A guarda de unicidade do e-mail: {@code true} somente quando o {@code UPDATE} mudou a
     * linha. O Video ja foi carregado e validado pela entidade no use case (ADR 0001 e 0002).
     */
    CompletableFuture<Boolean> marcarFalha(UUID id, Instant falhouEm, MotivoFalha motivo);

    /** A tabela `video` e o outbox (ADR 0003): grava a marca depois do publish ter saido. */
    CompletableFuture<Void> marcarComandoPublicado(UUID id, Instant publicadoEm);

    /** Idem, para o outro lado da politica de falhas: a publicacao de VideoFalhou. */
    CompletableFuture<Void> marcarFalhaPublicada(UUID id, Instant publicadoEm);

    /**
     * Vídeos RECEBIDO ou PROCESSANDO sem a marca do {@code ExtrairVideo}, com folga contra o
     * crash entre o INSERT e o publish: so entram aqui os recebidos antes de
     * {@code recebidosAntesDe} (ADR 0003). PROCESSANDO entra pelo resgate, que apaga a marca
     * (ticket 107). Ordenado por {@code recebidoEm}, lote limitado.
     */
    CompletableFuture<List<Video>> buscarComandosPendentes(Instant recebidosAntesDe, int tamanhoDoLote);

    /**
     * Vídeos FALHOU cujo {@code VideoFalhou} nunca foi publicado, com a <b>mesma</b> folga de
     * {@link #buscarComandosPendentes}, agora contra o crash entre a transicao para FALHOU e
     * o publish: so entram aqui os que falharam antes de {@code falhadosAntesDe} (ADR 0003,
     * ticket 050). O instante julgado e o {@code finalizadoEm}, que e onde o
     * {@code marcarFalha} grava quando a linha virou FALHOU. Ordenado por
     * {@code finalizadoEm}, lote limitado.
     */
    CompletableFuture<List<Video>> buscarFalhasPendentes(Instant falhadosAntesDe, int tamanhoDoLote);

    /** Videos PROCESSANDO cuja Extracao comecou antes de {@code iniciadosAntesDe} (ticket 106). */
    CompletableFuture<Long> contarProcessandoIniciadosAntesDe(Instant iniciadosAntesDe);

    /**
     * Videos RECEBIDO cujo {@code ExtrairVideo} foi marcado como publicado antes de
     * {@code publicadosAntesDe} (ticket 106). Os sem marca ficam de fora: sao os de
     * {@link #buscarComandosPendentes}.
     */
    CompletableFuture<Long> contarRecebidosComComandoPublicadoAntesDe(Instant publicadosAntesDe);
}
