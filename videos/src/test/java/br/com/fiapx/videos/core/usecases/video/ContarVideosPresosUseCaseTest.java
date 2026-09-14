package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Os dois criterios do ticket 106, sem banco: {@code PROCESSANDO} contado desde o inicio da
 * Extracao, e {@code RECEBIDO} contado desde a marca do comando. A condicao de fila vazia do
 * segundo nao mora aqui, e sim no alerta, que e quem enxerga o broker.
 */
class ContarVideosPresosUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");
    private static final Duration LIMIAR = Duration.ofMinutes(30);

    private GatewaysEmMemoria.Videos videos;
    private ContarVideosPresosUseCase useCase;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        useCase = new ContarVideosPresosUseCase(videos, LIMIAR);
    }

    @Test
    void processandoAlemDoLimiarDesdeOInicioEstaPreso() {
        processandoHa(Duration.ofMinutes(31));
        processandoHa(Duration.ofMinutes(29));

        var presos = useCase.executar().join();

        assertEquals(1, presos.processando());
    }

    @Test
    void oRelogioDoProcessandoEOInicioENaoOEnvio() {
        // Recebido ha uma hora, mas so pegou o trabalho agora: backlog legitimo, nao preso.
        var video = recebido();
        video.marcaComoIniciada(Instant.now().minus(Duration.ofMinutes(1)));
        videos.armazenados.put(video.id(), reconstituidoRecebidoHa(video, Duration.ofHours(1)));

        assertEquals(0, useCase.executar().join().processando());
    }

    @Test
    void recebidoComComandoPublicadoAlemDoLimiarEstaPreso() {
        recebidoComComandoPublicadoHa(Duration.ofMinutes(31));
        recebidoComComandoPublicadoHa(Duration.ofMinutes(29));

        var presos = useCase.executar().join();

        assertEquals(1, presos.recebidos());
        assertEquals(0, presos.processando());
    }

    @Test
    void recebidoSemMarcaNaoContaPorqueEDaReconciliacao() {
        // Sem marca, o comando nunca saiu, e a varredura do ADR 0003 o republica em 1 min. O
        // alerta so fala do que a reconciliacao nao alcanca.
        var video = recebido();
        videos.armazenados.put(video.id(), reconstituidoRecebidoHa(video, Duration.ofHours(2)));

        assertEquals(0, useCase.executar().join().recebidos());
    }

    @Test
    void terminalNuncaEstaPreso() {
        var concluido = recebido();
        concluido.marcaComoIniciada(Instant.now().minus(Duration.ofHours(2)));
        concluido.marcaComoConcluida(new ResultadoExtracao(Instant.now(), "p.zip", 1, 1L));
        videos.armazenados.put(concluido.id(), concluido);
        var falhou = recebido();
        falhou.marcaComoFalha(Instant.now(), MotivoFalha.TENTATIVAS_ESGOTADAS);
        videos.armazenados.put(falhou.id(), falhou);
        videos.comandoPublicadoEm.put(falhou.id(), Instant.now().minus(Duration.ofHours(2)));

        var presos = useCase.executar().join();

        assertEquals(0, presos.processando());
        assertEquals(0, presos.recebidos());
    }

    private void processandoHa(Duration idade) {
        var video = recebido();
        video.marcaComoIniciada(Instant.now().minus(idade));
        videos.armazenados.put(video.id(), video);
    }

    private void recebidoComComandoPublicadoHa(Duration idade) {
        var video = recebido();
        videos.armazenados.put(video.id(), video);
        videos.comandoPublicadoEm.put(video.id(), Instant.now().minus(idade));
    }

    private static Video recebido() {
        return Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("k");
    }

    private static Video reconstituidoRecebidoHa(Video video, Duration idade) {
        return Video.reconstituir(video.id(), video.nome(), video.tamanhoBytes(), video.dono(),
                video.chaveVideo(), video.estado(), Instant.now().minus(idade), video.iniciadaEm(),
                video.finalizadoEm(), video.chavePacote(), video.quantidadeFrames(),
                video.tamanhoPacoteBytes(), video.motivo());
    }
}
