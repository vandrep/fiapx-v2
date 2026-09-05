package br.com.fiapx.videos.core.interfaces.gateway;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
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
     * uma vez so.
     */
    CompletableFuture<Boolean> marcarIniciada(UUID id);

    /**
     * Mesma guarda de {@link #marcarIniciada}, agora para CONCLUIDO — e saindo de RECEBIDO
     * <b>ou</b> de PROCESSANDO, porque a {@code ExtracaoConcluida} pode chegar antes da
     * {@code ExtracaoIniciada} (ticket 027, ADR 0002).
     */
    CompletableFuture<Boolean> marcarConcluida(UUID id,
                                               Instant concluidaEm,
                                               String chavePacote,
                                               int quantidadeFrames,
                                               long tamanhoPacoteBytes);

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
     * Vídeos RECEBIDO cujo {@code ExtrairVideo} nunca foi publicado, com folga contra o
     * crash entre o INSERT e o publish: so entram aqui os recebidos antes de
     * {@code recebidosAntesDe} (ADR 0003). Ordenado por {@code recebidoEm}, lote limitado.
     */
    CompletableFuture<List<Video>> buscarComandosPendentes(Instant recebidosAntesDe, int tamanhoDoLote);

    /**
     * Vídeos FALHOU cujo {@code VideoFalhou} nunca foi publicado. Sem folga de tempo: a
     * transicao para FALHOU e a publicacao sao consecutivas no mesmo caminho, e o risco de
     * corrida com uma varredura concorrente e tolerado (ADR 0003).
     */
    CompletableFuture<List<Video>> buscarFalhasPendentes(int tamanhoDoLote);
}
