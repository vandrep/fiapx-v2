package br.com.fiapx.extracao.framework.shutdown;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * Segura o desligamento do {@code extracao} ate a Extracao em voo terminar e dar ack, para
 * que um deploy nao gaste uma das tres entregas de {@code extracao.extrair}
 * (ticket 030, ticket 035; achado em docs/pesquisa/rabbitmq-retry-dlq.md §8 e §9).
 *
 * <h2>Por que aqui, e nao num {@code ShutdownListener}</h2>
 *
 * O ticket 035 esbocou um {@code io.quarkus.runtime.shutdown.ShutdownListener}, que faria
 * {@code quarkus.shutdown.timeout} valer para a Extracao. <b>Aplicacao nao consegue registrar
 * um.</b> A lista vem de {@code ShutdownRecorder.setListeners(...)}, alimentada por
 * {@code ShutdownListenerBuildItem} — um build item de <i>augmentation</i>, que embrulha uma
 * <i>instancia</i> criada em tempo de build. Produzi-lo exige uma extensao Quarkus com modulo
 * de deployment; nao ha caminho por bean CDI. O unico atalho que o {@code quarkus-arc} abre e
 * o {@code ArcShutdownListener}, que dispara {@code ShutdownDelayInitiatedEvent} — e so
 * quando {@code quarkus.shutdown.delay-enabled=true}.
 *
 * <p>Mesmo que desse, seria o lugar errado: {@code ShutdownRecorder.runShutdown()} roda
 * <i>inteiro</i> antes de {@code doStop()}, e e {@code doStop()} que chama
 * {@code Arc.shutdown()} e portanto o {@code RabbitMQConnector.terminate()}. Entre o fim do
 * dreno e o fechamento do canal caberia a fase graciosa de HTTP inteira, com o conector ainda
 * consumindo — janela de sobra para o broker entregar a proxima mensagem, que morreria no
 * fechamento gastando exatamente a entrega que este codigo existe para poupar.
 *
 * <p>Este observer usa o mesmo evento CDI que o conector observa
 * ({@code @BeforeDestroyed(ApplicationScoped.class)}), com prioridade 10 contra 50.
 * Primeiro cancela a assinatura de extrair-video, mantendo o canal e os publicadores
 * abertos; depois espera o ack. Sem cancelar antes, o ack devolveria credito ao broker
 * para entregar outra mensagem que morreria no fechamento (medido no ticket 035).
 * {@link CancelamentoDoConsumo} isola o acesso aos campos privados do SmallRye.
 *
 * <h2>O teto e nosso, porque nenhum teto do Quarkus alcanca esta fase</h2>
 *
 * {@code quarkus.shutdown.timeout} limita so a fase {@code shutdown()} do
 * {@code ShutdownRecorder}, que ja passou quando este observer roda — e nem a fase
 * {@code preShutdown()} tem teto ({@code preShutdown.await()} e incondicional). Logo o teto
 * precisa ser deste bean, e precisa ser <b>menor</b> que o {@code stop_grace_period} do
 * servico no {@code docker-compose.yml}: se o Docker mandar {@code SIGKILL} durante o dreno,
 * a espera nao poupou entrega nenhuma e ainda atrasou o deploy. Sao tres numeros que precisam
 * ficar coerentes — ver {@code fiapx.extracao.dreno-timeout-segundos} no
 * {@code application.properties}.
 *
 * <h2>O que ele nao promete</h2>
 *
 * SIGKILL, OOM, queda de no ou de rede e trabalho que excede o teto continuam
 * gastando entrega. A ponte de cancelamento e especifica do SmallRye 4.32.1:
 * uma atualizacao exige repetir o ensaio de redeploy antes de mudar a guarda de versao.
 */
@ApplicationScoped
public class DrenoDaExtracao {

    private static final Logger LOG = Logger.getLogger(DrenoDaExtracao.class);

    /**
     * Menor que o {@code @Priority(50)} do {@code RabbitMQConnector.terminate()}, que e quem
     * fecha o canal. Nao ha nada entre os dois de proposito.
     */
    private static final int ANTES_DO_CONECTOR_RABBITMQ = 10;

    /**
     * Sem {@code defaultValue}: o numero mora no {@code application.properties}, e um default
     * aqui seria uma segunda copia dele, livre para divergir em silencio. Faltando a
     * propriedade, o boot falha alto — que e o que se quer de um numero que so serve se for
     * coerente com o {@code stop_grace_period} do Compose.
     */
    @ConfigProperty(name = "fiapx.extracao.dreno-timeout-segundos")
    long tetoSegundos;

    @Inject
    CancelamentoDoConsumo cancelamento;

    /**
     * Portao e contagem sao um estado so, sob uma trava so. Separa-los em {@code volatile} +
     * {@code AtomicInteger} deixa passar a corrida que este bean existe para fechar: entre o
     * consumidor ler "portao aberto" e registrar a entrada cabe o fechamento inteiro do
     * portao, e a Extracao aceita nesse instante morre no {@code SIGTERM} sem ninguem por ela.
     */
    private final Object trava = new Object();
    private int emVoo;
    private boolean portaoFechado;

    /**
     * Registra uma Extracao que vai comecar. Devolve {@code false} quando o desligamento ja
     * comecou — ai o consumidor nao pode processar a mensagem, e tambem nao deve nackear:
     * requeue explicito gasta a mesma entrega que o fechamento do canal ja vai gastar, e ainda
     * antecipa a corrida.
     */
    public boolean entrar() {
        synchronized (trava) {
            if (portaoFechado) {
                return false;
            }
            emVoo++;
            return true;
        }
    }

    /** Baixa uma Extracao registrada por {@link #entrar()}. Chamar sempre, e depois do ack. */
    public void sair() {
        synchronized (trava) {
            emVoo--;
            trava.notifyAll();
        }
    }

    /** Quantas Extracoes estao em voo agora. Existe para o teste e para o log do dreno. */
    public int emVoo() {
        synchronized (trava) {
            return emVoo;
        }
    }

    /**
     * Fecha o portao e espera as Extracoes ja em voo terminarem, ate {@code teto}.
     *
     * @return {@code true} se drenou; {@code false} se o teto estourou com trabalho em voo —
     *         e nesse caso a mensagem <b>vai</b> ser reenfileirada, exatamente como antes
     *         deste bean existir.
     */
    public boolean drenar(Duration teto) {
        long limite = System.nanoTime() + teto.toNanos();
        synchronized (trava) {
            portaoFechado = true;
            while (emVoo > 0) {
                long restanteMillis = (limite - System.nanoTime()) / 1_000_000L;
                if (restanteMillis <= 0) {
                    return false;
                }
                try {
                    trava.wait(restanteMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    void drenarAntesDeOConectorFecharOCanal(
            @Observes @Priority(ANTES_DO_CONECTOR_RABBITMQ) @BeforeDestroyed(ApplicationScoped.class) Object evento) {
        Duration teto = Duration.ofSeconds(tetoSegundos);
        int pendentes = emVoo();
        if (pendentes > 0) {
            LOG.infof("SIGTERM com %d Extracao(oes) em voo; segurando o desligamento por ate %ds",
                    pendentes, teto.toSeconds());
        }

        // Cancela antes de fechar o portao: o trabalho ja entregue continua podendo
        // entrar enquanto o broker confirma o cancelamento. O ack em voo permanece
        // na cadeia do consumidor, porque so cancelamos a assinatura a montante.
        // Cancelamento e espera dividem o mesmo teto, inclusive na replica ociosa.
        long inicio = System.nanoTime();
        boolean cancelou = cancelamento.cancelar(teto);
        Duration restante = teto.minusNanos(System.nanoTime() - inicio);
        boolean drenou = drenar(restante.isNegative() ? Duration.ZERO : restante);
        long decorridoSegundos = (System.nanoTime() - inicio) / 1_000_000_000L;

        if (!cancelou) {
            LOG.error("cancelamento nao retornou normalmente no teto: nao ha garantia de poupar entrega");
        }
        if (!drenou) {
            LOG.errorf("teto de dreno de %ds estourado com %d Extracao(oes) ainda em voo apos %ds:"
                    + " a mensagem vai ser reenfileirada e a entrega, gasta", teto.toSeconds(), emVoo(),
                    decorridoSegundos);
        } else if (pendentes > 0) {
            LOG.infof("Extracao em voo terminou e deu ack em %ds; liberando o desligamento",
                    decorridoSegundos);
        }
    }
}
