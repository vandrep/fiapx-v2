package br.com.fiapx.extracao.framework.shutdown;

import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQConnector;
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQConnectorIncomingConfiguration;
import io.smallrye.reactive.messaging.rabbitmq.internals.IncomingRabbitMQChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ponte temporaria para o basic.cancel que SmallRye 4.32.1 nao expoe (ticket 035).
 * Cancela somente extrair-video, preservando o canal de ack e os publicadores.
 * A dependencia de campos privados e validada no boot, inclusive a versao: atualizar
 * o conector exige medir novamente o redeploy, em vez de perder a garantia em silencio.
 */
@ApplicationScoped
public class CancelamentoDoConsumo {

    private static final Logger LOG = Logger.getLogger(CancelamentoDoConsumo.class);

    @Inject
    @Connector(RabbitMQConnector.CONNECTOR_NAME)
    RabbitMQConnector conector;

    private IncomingRabbitMQChannel entrada;

    void validarCompatibilidade(@Observes StartupEvent evento) {
        String recurso = "/META-INF/maven/io.smallrye.reactive/"
                + "smallrye-reactive-messaging-rabbitmq/pom.properties";
        try (var fonte = RabbitMQConnector.class.getResourceAsStream(recurso)) {
            var propriedades = new Properties();
            if (fonte == null) {
                throw new IllegalStateException("versao do conector RabbitMQ indisponivel");
            }
            propriedades.load(fonte);
            if (!"4.32.1".equals(propriedades.getProperty("version"))) {
                throw new IllegalStateException("revalidar o dreno do ticket 035 para RabbitMQ "
                        + propriedades.getProperty("version"));
            }
            var entradas = RabbitMQConnector.class.getDeclaredField("incomings");
            var configuracao = IncomingRabbitMQChannel.class.getDeclaredField("config");
            entradas.setAccessible(true);
            configuracao.setAccessible(true);
            // Campos de um proxy CDI nao sao os campos da instancia contextual.
            var canais = (List<?>) entradas.get(ClientProxy.unwrap(conector));
            for (Object objeto : canais) {
                var canal = (IncomingRabbitMQChannel) objeto;
                var config = (RabbitMQConnectorIncomingConfiguration) configuracao.get(canal);
                if ("extrair-video".equals(config.getChannel())) {
                    entrada = canal;
                    return;
                }
            }
            throw new IllegalStateException("canal extrair-video ausente no conector RabbitMQ");
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("conector incompativel com o dreno do ticket 035", e);
        }
    }

    /**
     * Retorno normal de terminate(), nao confirmacao AMQP: falhas assincronas do
     * cancelamento sao reportadas pelo Mutiny como dropped exceptions (ticket 035).
     */
    public boolean cancelar(Duration teto) {
        // Boot incompleto: nao ha assinatura validada a cancelar.
        if (entrada == null) {
            return true;
        }
        // receiver.cancel() faz RPC sincrona com o broker. Ela tambem precisa caber
        // no teto do dreno; uma rede quebrada nao pode prender o observer sem limite.
        var tarefa = new FutureTask<Void>(() -> {
            entrada.terminate();
            return null;
        });
        Thread.ofVirtual().name("cancelar-extrair-video").start(tarefa);
        try {
            tarefa.get(teto.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("cancelamento de extrair-video interrompido", e);
        } catch (ExecutionException | TimeoutException e) {
            LOG.error("nao foi possivel cancelar extrair-video dentro do teto do dreno", e);
        } finally {
            tarefa.cancel(true);
        }
        return false;
    }
}
