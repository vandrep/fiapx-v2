package br.com.fiapx.extracao.framework.dispatcher;

import br.com.fiapx.extracao.core.usecases.extracao.ProcessarExtracaoUseCase;
import br.com.fiapx.extracao.framework.shutdown.DrenoDaExtracao;
import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que este teste guarda e o contrato entre o consumidor e o {@link DrenoDaExtracao}
 * (ticket 035), nao o pipeline de extracao — o {@link ProcessarExtracaoUseCase} tem os seus.
 * Tres coisas nao podem quebrar: ack e nack continuam significando o que o
 * {@code failure-strategy=requeue} do canal espera (ADR 0001), o {@code sair()} do dreno
 * acontece <b>depois</b> do ack, e o portao fechado nao processa nem responde nada.
 */
class ExtrairVideoConsumerTest {

    private static final int SEM_ACK = -1;

    private DrenoDaExtracao dreno;
    private UUID idVideo;
    /** O que o consumidor respondeu ao broker. Enum, e nao string: os dois valores sao o
     *  contrato inteiro deste teste com o failure-strategy do canal, e um erro de digitacao
     *  numa string faria a assercao passar a comparar nada com nada. */
    private enum Resposta { ACK, NACK }

    private final AtomicReference<Resposta> resposta = new AtomicReference<>();
    private final AtomicReference<Throwable> motivoDoNack = new AtomicReference<>();
    /** Quantas Extracoes o dreno via no instante do ack. Tem que ser 1, nunca 0. */
    private final AtomicInteger emVooNoAck = new AtomicInteger(SEM_ACK);

    @BeforeEach
    void preparar() {
        dreno = new DrenoDaExtracao();
        idVideo = UUID.randomUUID();
        resposta.set(null);
        motivoDoNack.set(null);
        emVooNoAck.set(SEM_ACK);
    }

    @Test
    void extracaoQueCompletaAckeiaEAvisaODrenoSoDepoisDoAck() throws Exception {
        var consumidor = consumidorCom(id -> CompletableFuture.completedFuture(null));

        consumir(consumidor).get(5, TimeUnit.SECONDS);

        assertEquals(Resposta.ACK, resposta.get());
        assertEquals(1, emVooNoAck.get(),
                "o dreno soltou a Extracao antes do ack, e o ack e a metade que importa:"
                        + " ackear num canal ja fechado nao poupa entrega nenhuma");
        assertEquals(0, dreno.emVoo(), "sair() nao foi chamado ao fim");
    }

    @Test
    void extracaoQueFalhaNackeiaComOMesmoMotivoESoltaODreno() throws Exception {
        var falha = new IllegalStateException("MinIO fora do ar");
        var consumidor = consumidorCom(id -> CompletableFuture.failedFuture(falha));

        consumir(consumidor).get(5, TimeUnit.SECONDS);

        assertEquals(Resposta.NACK, resposta.get());
        assertSame(falha, causaRaiz(motivoDoNack.get()),
                "o failure-strategy=requeue precisa receber a causa real: e ela que aparece no"
                        + " log do conector quando o x-delivery-limit esgota");
        assertEquals(0, dreno.emVoo());
    }

    /**
     * O caminho que vaza sem `deferred`: uma excecao lancada de forma sincrona pelo controller
     * — um NPE de configuracao, por exemplo — escapa antes de existir Uni, entao nao ha cadeia
     * para o {@code eventually} pendurar, e o {@code sair()} nunca acontece. O dano nao e este
     * Video: e o {@code emVoo} que fica em 1 para sempre, e todo desligamento seguinte desta
     * replica passa a esperar os 420s inteiros para nada.
     */
    @Test
    void falhaSincronaDoControllerNaoDeixaODrenoContandoParaSempre() throws Exception {
        var consumidor = consumidorCom(id -> {
            throw new IllegalStateException("configuracao quebrada");
        });

        consumir(consumidor).get(5, TimeUnit.SECONDS);

        assertEquals(Resposta.NACK, resposta.get());
        assertEquals(0, dreno.emVoo(), "o dreno ficou contando uma Extracao que nao existe mais");
    }

    @Test
    void comOPortaoFechadoNaoProcessaNaoAckeiaENaoNackeia() {
        var comecou = new AtomicBoolean(false);
        var consumidor = consumidorCom(id -> {
            comecou.set(true);
            return CompletableFuture.completedFuture(null);
        });
        dreno.drenar(Duration.ofSeconds(1));

        var futuro = consumir(consumidor);

        assertFalse(comecou.get(), "ffmpeg nao pode comecar com o desligamento ja em curso");
        assertNull(resposta.get(),
                "ack ou nack aqui gasta a mesma entrega que o fechamento do canal ja vai gastar");
        assertThrows(TimeoutException.class, () -> futuro.get(200, TimeUnit.MILLISECONDS),
                "a mensagem fica pendurada ate o conector fechar o canal e o broker reenfileirar");
        futuro.cancel(true);
    }

    private CompletableFuture<Void> consumir(ExtrairVideoConsumer consumidor) {
        Message<ExtrairVideo> mensagem = Message.of(
                new ExtrairVideo(idVideo, "videos/x.mp4", "pacotes/x.zip"),
                () -> {
                    emVooNoAck.set(dreno.emVoo());
                    resposta.set(Resposta.ACK);
                    return CompletableFuture.completedFuture(null);
                },
                falha -> {
                    resposta.set(Resposta.NACK);
                    motivoDoNack.set(falha);
                    return CompletableFuture.completedFuture(null);
                });
        return consumidor.consumir(mensagem).subscribeAsCompletionStage();
    }

    /**
     * O {@code ExtracaoController} e classe pura, sem anotacao de framework, entao o duble mais
     * barato e uma subclasse anonima dele — nao ha container nem mock a montar. Os dois use
     * cases chegam nulos de proposito: o unico metodo que este teste exercita esta sobrescrito,
     * e um nulo reprova alto se algum dia deixar de estar.
     */
    private ExtrairVideoConsumer consumidorCom(Function<UUID, CompletableFuture<Void>> pipeline) {
        var consumidor = new ExtrairVideoConsumer();
        consumidor.dreno = dreno;
        consumidor.extracaoController = new ExtracaoController(null, null) {
            @Override
            public CompletableFuture<Void> processarExtrairVideo(UUID id, String chaveVideo, String chaveDestino) {
                return pipeline.apply(id);
            }
        };
        return consumidor;
    }

    /** O Mutiny embrulha a falha do CompletionStage em CompletionException no caminho. */
    private static Throwable causaRaiz(Throwable falha) {
        return falha.getCause() == null ? falha : falha.getCause();
    }
}
