package br.com.fiapx.extracao.core.interfaces.gateway;

import br.com.fiapx.extracao.core.entities.ResultadoExtracao;
import br.com.fiapx.extracao.core.exceptions.FalhaPermanenteDeExtracaoException;
import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * O pipeline de ffmpeg visto pelo dominio: mede a duracao, extrai frames a 1 fps, confere a
 * contagem contra a duracao, e empacota em ZIP {@code STORED} (ticket 006). Quem implementa
 * e quem sabe que por baixo e um processo externo — o core so ve "vira {@link
 * ResultadoExtracao} ou uma das duas excecoes de falha".
 *
 * <p>{@code tetoDuracao} e passado pelo use case (regra de negocio, ticket 011); a medicao
 * em si e mecanica do adapter, que ja roda o `ffprobe` para conferir a contagem de frames —
 * cobrar a duracao ali e nao gastar um segundo processo.
 */
public interface ExtracaoDeFramesGateway {

    /**
     * @throws FalhaPermanenteDeExtracaoException arquivo invalido, formato nao suportado, sem
     *         stream de video, ou duracao acima de {@code tetoDuracao}
     * @throws FalhaTransitoriaDeExtracaoException I/O, memoria, disco, ou qualquer exit code
     *         que a classificacao do ticket 006 nao reconhece como permanente
     */
    CompletableFuture<ResultadoExtracao> processar(Path video,
                                                    Path diretorioDeTrabalho,
                                                    Path destinoZip,
                                                    Duration tetoDuracao);
}
