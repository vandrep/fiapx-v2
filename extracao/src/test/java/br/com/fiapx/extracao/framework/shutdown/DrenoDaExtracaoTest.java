package br.com.fiapx.extracao.framework.shutdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O dreno e um primitivo de concorrencia, e o que ele promete e um invariante, nao um tempo:
 * <b>se {@code drenar} devolveu true, nao ha nem havera Extracao em voo</b>. Por isso o teste
 * mais importante aqui e o de corrida ({@link #drenoNuncaDeclaraCanalLivreComExtracaoEmVoo()}),
 * repetido: a versao ingenua desta classe — um {@code volatile boolean} para o portao e um
 * {@code AtomicInteger} para a contagem — passa nos quatro testes de caminho feliz e falha
 * nesse, porque entre o {@code if (portaoAberto)} do consumidor e o {@code emVoo++} cabe o
 * fechamento do portao inteiro.
 */
class DrenoDaExtracaoTest {

    private static final Duration TETO = Duration.ofSeconds(5);

    @Test
    void semExtracaoEmVooODrenoLiberaNaHora() {
        var dreno = new DrenoDaExtracao();

        assertTrue(dreno.drenar(TETO), "sem trabalho em voo o dreno nao tem o que esperar");
    }

    @Test
    void drenoEsperaAExtracaoEmVooTerminar() throws Exception {
        var dreno = new DrenoDaExtracao();
        assertTrue(dreno.entrar(), "portao aberto deve deixar a Extracao entrar");

        var saiu = new AtomicBoolean(false);
        var thread = new Thread(() -> {
            dormir(200);
            saiu.set(true);
            dreno.sair();
        });
        thread.start();

        assertTrue(dreno.drenar(TETO), "o dreno deveria ter esperado o sair()");
        assertTrue(saiu.get(), "o dreno liberou antes de a Extracao em voo terminar");
        thread.join();
    }

    @Test
    void drenoDesisteNoTetoQuandoAExtracaoNaoTermina() {
        var dreno = new DrenoDaExtracao();
        dreno.entrar();

        long inicio = System.nanoTime();
        boolean drenou = dreno.drenar(Duration.ofMillis(300));
        long decorrido = (System.nanoTime() - inicio) / 1_000_000;

        assertFalse(drenou, "com Extracao presa o dreno tem que confessar que nao drenou");
        // Folga de milissegundos, nao de ordem de grandeza: a divisao inteira do restante em
        // milissegundos trunca, entao um teto de 300 ms pode encerrar em 299. O que este
        // numero reprova e desistir na hora, sem esperar nada.
        assertTrue(decorrido >= 250, "desistiu antes do teto: " + decorrido + "ms");
    }

    /**
     * O caso que passou batido na primeira versao: a replica ociosa. Com a guarda de saida
     * antecipada ({@code if (pendentes == 0) return;}) antes de {@code drenar()}, o portao
     * nunca fechava numa replica sem trabalho — e uma mensagem entregue entre o observer e o
     * {@code terminate()} do conector comecava uma Extracao inteira para morrer no fechamento
     * do canal. Exatamente o que o bean existe para impedir.
     */
    @Test
    void replicaOciosaTambemFechaOPortao() {
        var dreno = new DrenoDaExtracao();
        assertEquals(0, dreno.emVoo(), "pre-condicao: nada em voo");

        assertTrue(dreno.drenar(TETO), "sem trabalho em voo o dreno libera na hora");
        assertFalse(dreno.entrar(),
                "portao ficou aberto numa replica ociosa: a proxima mensagem entregue viraria"
                        + " uma Extracao morta pelo SIGTERM");
    }

    @Test
    void portaoFechadoRecusaExtracaoNova() {
        var dreno = new DrenoDaExtracao();
        dreno.drenar(TETO);

        assertFalse(dreno.entrar(), "depois do dreno nenhuma Extracao nova pode comecar");
        assertEquals(0, dreno.emVoo());
    }

    /**
     * O consumidor faz {@code entrar()} e, se aceito, roda a Extracao; o hook de shutdown faz
     * {@code drenar()}. Os dois correm em threads diferentes e sem coordenacao nenhuma. A
     * unica coisa que nao pode acontecer e o dreno declarar o canal livre enquanto alguem que
     * acabou de ser aceito ainda vai chamar o ffmpeg.
     */
    @RepeatedTest(200)
    void drenoNuncaDeclaraCanalLivreComExtracaoEmVoo() throws Exception {
        var dreno = new DrenoDaExtracao();
        // Rendezvous por spin, e nao CyclicBarrier/CountDownLatch com timeout: rodando junto
        // com o resto da suite (Dev Services, containers), o agendador chega a segurar uma
        // thread por mais que qualquer timeout razoavel, e a barreira quebrava — reprovando
        // por saturacao do host, nao por defeito no dreno. Um spin nao tem como expirar, e
        // ainda deixa a corrida mais apertada: a thread ja esta pronta para agir no instante
        // em que a bandeira vira.
        var largada = new AtomicBoolean(false);
        var entrou = new AtomicBoolean(false);

        var consumidor = new Thread(() -> {
            while (!largada.get()) {
                Thread.onSpinWait();
            }
            entrou.set(dreno.entrar());
        });
        consumidor.start();

        largada.set(true);
        // 50ms, e nao 500: nas repeticoes em que o consumidor entra antes do portao fechar,
        // este teto e esperado inteiro (o consumidor nunca chama sair()), entao ele multiplica
        // por 200 e vira o custo do build. O teto nao participa da corrida que o teste caca —
        // ela acontece no primeiro instante de drenar() —, so do quanto se espera depois dela.
        boolean drenou = dreno.drenar(Duration.ofMillis(50));
        consumidor.join();

        if (drenou) {
            assertEquals(0, dreno.emVoo(),
                    "dreno declarou canal livre com Extracao em voo — a aceita foi morta pelo SIGTERM");
            assertFalse(entrou.get(),
                    "entrar() aceitou uma Extracao que o dreno ja tinha dado como inexistente");
        } else {
            // Aceito antes de o portao fechar: o dreno esperou e, como este consumidor nunca
            // chama sair(), estourou o teto. Confissao correta, nao defeito.
            assertTrue(entrou.get(), "dreno estourou o teto sem ninguem em voo");
        }
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
