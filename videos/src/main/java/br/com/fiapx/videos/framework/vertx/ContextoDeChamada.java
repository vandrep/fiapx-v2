package br.com.fiapx.videos.framework.vertx;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

/**
 * Devolve a continuacao de uma operacao assincrona ao contexto Vert.x de quem a montou.
 *
 * <p>O que completa um future aqui nem sempre roda no contexto da requisicao: o SDK da AWS
 * completa na propria event loop, e o teto do publish do ticket 104 completa na thread do timer
 * do JDK. Fora do contexto duplicado o Panache nao acha a sessao, e o {@code MDC.put} cai numa
 * ThreadLocal compartilhada que ninguem solta — o vazamento do ticket 063.
 *
 * <p>O contexto e capturado <b>na montagem</b>, e por isso este metodo tem de ser chamado na
 * thread de quem chama, nao dentro de uma continuacao. Sem contexto corrente (teste de unidade,
 * scheduler em worker), a operacao volta como veio.
 */
public final class ContextoDeChamada {

    private ContextoDeChamada() {
    }

    public static <T> Uni<T> retomarNele(Uni<T> operacao) {
        Context contexto = Vertx.currentContext();
        if (contexto == null) {
            return operacao;
        }
        return operacao.emitOn(comando -> contexto.runOnContext(ignorado -> comando.run()));
    }
}
