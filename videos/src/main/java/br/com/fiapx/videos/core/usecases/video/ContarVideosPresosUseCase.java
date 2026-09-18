package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Conta os Videos presos pelos dois criterios do ticket 106. Nao resgata nada: resgate
 * automatico republicaria durante o pico, a armadilha que o ADR 0003 ja recusou. Quem le o
 * numero e o alerta, e o desfecho continua sendo de um humano.
 *
 * <p>Os dois criterios medem idades diferentes, e a diferenca e o ponto:
 *
 * <ul>
 *   <li>{@code PROCESSANDO} conta desde o <b>inicio da Extracao</b>, nao desde o envio — um
 *       Video que esperou uma hora na fila e acabou de ser pego nao esta preso;</li>
 *   <li>{@code RECEBIDO} conta desde a <b>marca do comando</b>. Sem marca o comando nunca
 *       saiu, e isso e da varredura do ADR 0003. Com marca, a idade sozinha nao basta: durante
 *       um pico ele e backlog legitimo. A outra metade do criterio — {@code extracao.extrair}
 *       sem mensagem pronta — mora no alerta, porque so ele enxerga o broker.</li>
 * </ul>
 *
 * <p>As duas contagens rodam em sequencia, pelo mesmo motivo da reconciliacao: a sessao reativa
 * do Hibernate nao tolera duas consultas em voo no mesmo contexto.
 */
public class ContarVideosPresosUseCase {

    private final VideoGateway videoGateway;
    private final Duration limiar;

    public ContarVideosPresosUseCase(VideoGateway videoGateway, Duration limiar) {
        this.videoGateway = videoGateway;
        this.limiar = limiar;
    }

    public CompletableFuture<VideosPresos> executar() {
        var corte = Instant.now().minus(limiar);
        return videoGateway.contarProcessandoIniciadosAntesDe(corte)
                .thenCompose(processando -> videoGateway.contarRecebidosComComandoPublicadoAntesDe(corte)
                        .thenApply(recebidos -> new VideosPresos(processando, recebidos)));
    }

    /**
     * {@code recebidos} ainda nao e "preso" sozinho: vira preso quando a fila do comando esta
     * sem mensagem pronta, e quem faz essa conta e o alerta.
     */
    public record VideosPresos(long processando, long recebidos) {
    }
}
