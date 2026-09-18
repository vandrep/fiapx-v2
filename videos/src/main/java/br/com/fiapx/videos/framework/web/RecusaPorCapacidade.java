package br.com.fiapx.videos.framework.web;

import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Recusa o {@code POST /videos} que a replica nao comporta <b>antes de ler o corpo</b> (ticket
 * 108). O corpo inteiro, ate 200 MB, e gravado no volume de uploads antes de o
 * {@code Resource} rodar, e o disco cheio vira {@code 500} no meio do envio. Aqui a decisao
 * acontece so com os cabecalhos: envios em andamento no teto, ou {@code Content-Length} que nao
 * cabe no espaco livre, saem {@code 503} com {@code Retry-After}. A conta e de
 * {@link CapacidadeDoEnvio}.
 *
 * <p><b>A ordem da rota e o que torna isto verdade.</b> Hoje quem le o multipart e o
 * {@code FormBodyHandler} do Quarkus REST, dentro da rota do JAX-RS
 * ({@link RouteConstants#ROUTE_ORDER_DEFAULT}); o body handler global do Vert.x nao esta instalado.
 * Se alguma extensao o instalar, ele roda em {@link RouteConstants#ROUTE_ORDER_BODY_HANDLER}. Esta
 * rota fica antes dos dois. Quem prova e o {@code RecusaPorCapacidadeTest}: com a recusa esperando
 * o corpo, a resposta nao chega e ele reprova por timeout de leitura.
 *
 * <p>Por isso tambem roda <b>antes da autenticacao</b>: um envio sem token recebe {@code 503}
 * quando nao ha vaga, e nao {@code 401}. O que se protege e o volume, e a vaga e decidida antes de
 * qualquer um ler o corpo, inclusive quem autentica.
 *
 * <p>Recusa explicita nao e Video perdido: nada foi gravado e o sistema nao assumiu o Video.
 * {@code Content-Length} acima do teto do corpo passa direto, para o {@code 413} do Vert.x
 * continuar respondendo por ele.
 */
@ApplicationScoped
public class RecusaPorCapacidade {

    private static final Logger LOG = Logger.getLogger(RecusaPorCapacidade.class);

    /**
     * Sem valor, o teto e derivado do volume: quantos corpos de 200 MB cabem nele. Existe para a
     * calibracao e para o perfil de teste, que precisa de um teto que um teste consiga ocupar.
     */
    @ConfigProperty(name = "fiapx.borda.teto-de-envios-simultaneos")
    Optional<Integer> tetoConfigurado;

    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize tetoDoCorpo;

    @Inject
    VolumeDeUploads volume;

    private long tetoDoCorpoEmBytes;
    private CapacidadeDoEnvio capacidade;

    void registrar(@Observes Router router) {
        tetoDoCorpoEmBytes = tetoDoCorpo.asLongValue();
        var tetoDeEnvios = tetoConfigurado.orElseGet(
                () -> CapacidadeDoEnvio.tetoDerivado(volume.tamanho(), tetoDoCorpoEmBytes));
        capacidade = new CapacidadeDoEnvio(tetoDeEnvios, tetoDoCorpoEmBytes, volume::espacoLivre);
        LOG.infof("Teto de envios simultaneos nesta replica: %d (%s)", tetoDeEnvios,
                tetoConfigurado.isPresent() ? "configurado" : "derivado do volume de uploads");

        router.route(HttpMethod.POST, "/videos")
                .order(RouteConstants.ROUTE_ORDER_BODY_HANDLER - 1)
                .handler(this::decidir);
    }

    private void decidir(RoutingContext contexto) {
        var declarado = tamanhoDeclarado(contexto);
        if (declarado.isPresent() && declarado.getAsLong() > tetoDoCorpoEmBytes) {
            contexto.next();
            return;
        }
        switch (capacidade.reservar(declarado)) {
            case CapacidadeDoEnvio.Aceita aceita -> {
                contexto.addEndHandler(fim -> aceita.reserva().liberar());
                contexto.next();
            }
            case CapacidadeDoEnvio.Recusada recusada -> recusar(contexto, recusada.motivo());
        }
    }

    /**
     * O problem+json e escrito aqui, e nao por {@code ExceptionMapper}: o JAX-RS ainda nao entrou.
     * {@code Connection: close} porque o resto do corpo nao vai ser lido, e o cliente que o
     * respeita para de manda-lo.
     */
    private static void recusar(RoutingContext contexto, String motivo) {
        LOG.warnf("Envio recusado por capacidade: %s", motivo);
        var status = HttpResponseStatus.SERVICE_UNAVAILABLE.code();
        var problema = ProblemDetail.de(status, "Capacidade esgotada", ProblemDetail.comEsperaSugerida(motivo));
        contexto.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ProblemDetail.ESPERA_SUGERIDA_EM_SEGUNDOS))
                .putHeader(HttpHeaders.CONTENT_TYPE, ProblemDetail.MEDIA_TYPE)
                .putHeader(HttpHeaders.CONNECTION, HttpHeaders.CLOSE)
                .end(Json.encode(problema));
    }

    /** Cabecalho ausente ou malformado conta como nao declarado; o malformado o Vert.x recusa depois. */
    private static OptionalLong tamanhoDeclarado(RoutingContext contexto) {
        var valor = contexto.request().getHeader(HttpHeaders.CONTENT_LENGTH);
        if (valor == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(valor.trim()));
        } catch (NumberFormatException malformado) {
            return OptionalLong.empty();
        }
    }
}
