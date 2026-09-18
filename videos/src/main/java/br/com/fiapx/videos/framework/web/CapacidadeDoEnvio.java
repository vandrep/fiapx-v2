package br.com.fiapx.videos.framework.web;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Quanto a replica ainda consegue receber de {@code POST /videos} (ticket 108). A conta e do
 * recurso <b>local</b> da borda, o volume de uploads, e nao do backlog da fila: a fila e o
 * amortecedor de pico, e recusar por ela trocaria "nao perder" por "nao aceitar" justamente no
 * pico.
 *
 * <p>Duas perguntas, nesta ordem: ha vaga entre os envios em andamento, e o tamanho declarado
 * cabe no espaco livre <b>menos o que os envios em andamento ainda vao gravar</b>. O espaco
 * livre lido agora nao desconta o corpo que outro envio esta recebendo, e sem a reserva dois
 * envios passariam cada um sozinho pela conta e encheriam o volume juntos. A reserva conta o
 * tamanho declarado, e o que ja foi gravado dele aparece tambem no espaco livre: a conta erra
 * para o lado de recusar, e so perto do volume cheio.
 *
 * <p>Envio sem {@code Content-Length} reserva o teto do corpo, que e o maximo que ele pode
 * gravar antes do {@code 413}.
 *
 * <p><b>A conta e da replica.</b> Com N replicas sobre o mesmo volume, como no overlay de carga,
 * cada uma deriva o teto sobre o volume inteiro e nao enxerga a reserva das outras: a protecao
 * contra "dois envios enchem o volume juntos" so vale inteira com uma replica, que e a demo.
 */
final class CapacidadeDoEnvio {

    private final int tetoDeEnvios;
    private final long tetoDoCorpoEmBytes;
    private final LongSupplier espacoLivre;

    private int emAndamento;
    private long reservado;

    CapacidadeDoEnvio(int tetoDeEnvios, long tetoDoCorpoEmBytes, LongSupplier espacoLivre) {
        this.tetoDeEnvios = tetoDeEnvios;
        this.tetoDoCorpoEmBytes = tetoDoCorpoEmBytes;
        this.espacoLivre = espacoLivre;
    }

    /**
     * Quantos envios de corpo maximo o volume comporta, e nunca menos que um: num volume menor que
     * um corpo, quem decide e o espaco livre.
     */
    static int tetoDerivado(long tamanhoDoVolume, long tetoDoCorpoEmBytes) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, tamanhoDoVolume / tetoDoCorpoEmBytes));
    }

    /** Sincronizado: cada event loop e uma thread, e as duas perguntas tem de ver o mesmo estado. */
    synchronized Decisao reservar(OptionalLong tamanhoDeclarado) {
        if (emAndamento >= tetoDeEnvios) {
            return new Recusada("Há " + emAndamento + " envios em andamento, o máximo desta réplica");
        }
        var bytes = tamanhoDeclarado.orElse(tetoDoCorpoEmBytes);
        var livre = espacoLivre.getAsLong() - reservado;
        if (bytes > livre) {
            return new Recusada("O envio declara " + megabytes(bytes) + " MB e o volume de uploads tem "
                    + megabytes(Math.max(0, livre)) + " MB livres");
        }
        emAndamento++;
        reservado += bytes;
        return new Aceita(new Reserva(bytes));
    }

    private synchronized void devolver(long bytes) {
        emAndamento--;
        reservado -= bytes;
    }

    private static long megabytes(long bytes) {
        return bytes / (1024 * 1024);
    }

    sealed interface Decisao permits Aceita, Recusada {
    }

    record Aceita(Reserva reserva) implements Decisao {
    }

    /** @param motivo o dado concreto, para o {@code detail} do problem+json */
    record Recusada(String motivo) implements Decisao {
    }

    /** A vaga de um envio. Liberar duas vezes devolve uma: o fim da resposta e o fechamento da conexao chegam os dois. */
    final class Reserva {

        private final long bytes;
        private final AtomicBoolean liberada = new AtomicBoolean();

        private Reserva(long bytes) {
            this.bytes = bytes;
        }

        void liberar() {
            if (liberada.compareAndSet(false, true)) {
                devolver(bytes);
            }
        }
    }
}
