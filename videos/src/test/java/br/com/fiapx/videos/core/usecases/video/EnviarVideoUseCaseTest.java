package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.exceptions.FormatoNaoSuportadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnviarVideoUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");
    private static final Path ARQUIVO = Path.of("/tmp/upload-123");
    /** Curto para o teste nao esperar; o valor de producao e da raiz de composicao. */
    private static final Duration TETO_DO_PUBLISH = Duration.ofMillis(100);

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.Arquivos arquivos;
    private GatewaysEmMemoria.ExtracaoEnvios extracao;
    private GatewaysEmMemoria.Presenter presenter;
    private EnviarVideoUseCase useCase;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        arquivos = new GatewaysEmMemoria.Arquivos();
        extracao = new GatewaysEmMemoria.ExtracaoEnvios();
        presenter = new GatewaysEmMemoria.Presenter();
        useCase = new EnviarVideoUseCase(arquivos, videos,
                new PublicarExtrairVideo(arquivos, extracao, videos), presenter, TETO_DO_PUBLISH);
    }

    @Test
    void oVideoEntraEFicaEmRecebido() {
        var video = useCase.executar(comando("ferias.mp4", "video/mp4")).join();

        assertEquals(EstadoVideo.RECEBIDO, video.estado());
        assertEquals(1, videos.armazenados.size());
        assertEquals(video.id() + "/original.mp4", video.chaveVideo());
        assertNotNull(presenter.recebido);
        assertEquals(video.id(), presenter.recebido.id());
    }

    @Test
    void publicaExtrairVideoEMarcaOComandoComoPublicado() {
        // ADR 0003: INSERT com marca nula -> publica -> UPDATE da marca.
        var video = useCase.executar(comando("ferias.mp4", "video/mp4")).join();

        assertEquals(List.of(video.id()), extracao.idsEnviados);
        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
    }

    @Test
    void oObjetoVaiParaOArmazenamentoAntesDaLinha() {
        // Ordem fixa: nenhum passo pode referenciar algo que ainda nao existe. Se a chave
        // esta na linha persistida, o gravarVideo ja tinha respondido.
        var video = useCase.executar(comando("ferias.mp4", "video/mp4")).join();

        assertEquals(ARQUIVO, arquivos.ultimoArquivoGravado);
        assertNotNull(videos.armazenados.get(video.id()).chaveVideo());
    }

    @Test
    void publishQueFalhaDepoisDoInsertAindaAceitaOVideoEAVarreduraOPublicaDepois() {
        // Ticket 104: o aceite e o commit da linha. Dali em diante o ADR 0003 cobre o comando.
        extracao.falharNoProximoEnvio(new IllegalStateException("broker bloqueado por alarme"));

        var video = useCase.executar(comando("ferias.mp4", "video/mp4")).join();

        assertEquals(EstadoVideo.RECEBIDO, video.estado());
        assertNotNull(presenter.recebido, "o 202 precisa do corpo do Video aceito");
        assertNull(videos.comandoPublicadoEm.get(video.id()), "sem confirmacao, sem marca");

        envelhecerAlemDaFolgaDaVarredura(video);
        var republicadas = reconciliacao().executar().join();

        assertEquals(1, republicadas.comandos());
        assertEquals(List.of(video.id(), video.id()), extracao.idsEnviados);
        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
    }

    @Test
    void publishQueNaoRespondeNaoSeguraOAceiteAlemDoTeto() throws Exception {
        extracao.segurarOProximoEnvio();

        var video = useCase.executar(comando("ferias.mp4", "video/mp4"))
                .get(TETO_DO_PUBLISH.toMillis() * 20, TimeUnit.MILLISECONDS);

        assertEquals(EstadoVideo.RECEBIDO, video.estado());
        assertNotNull(presenter.recebido);
        assertNull(videos.comandoPublicadoEm.get(video.id()));
    }

    @Test
    void publishConfirmadoDepoisDoTetoAindaGravaAMarca() {
        // O teto limita quanto a requisicao espera, nao o publish: confirmado tarde, o comando
        // saiu, e a marca impede a varredura de dobrar a Extracao.
        var confirmacao = extracao.segurarOProximoEnvio();
        var video = useCase.executar(comando("ferias.mp4", "video/mp4")).join();
        assertNull(videos.comandoPublicadoEm.get(video.id()));

        confirmacao.complete(null);

        assertNotNull(videos.comandoPublicadoEm.get(video.id()));
    }

    @Test
    void falhaDoArmazenamentoNaoAceitaOVideo() {
        arquivos.falhaAoGravar = new IllegalStateException("MinIO fora");

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(comando("ferias.mp4", "video/mp4")).join());

        assertEquals("MinIO fora", falha.getCause().getMessage());
        assertNull(presenter.recebido);
        assertTrue(videos.armazenados.isEmpty());
        assertTrue(extracao.idsEnviados.isEmpty());
    }

    @Test
    void falhaDoInsertNaoAceitaOVideo() {
        videos.falhaAoAdicionar = new IllegalStateException("Postgres fora");

        var falha = assertThrows(CompletionException.class,
                () -> useCase.executar(comando("ferias.mp4", "video/mp4")).join());

        assertEquals("Postgres fora", falha.getCause().getMessage());
        assertNull(presenter.recebido);
        assertTrue(extracao.idsEnviados.isEmpty(), "sem linha nao ha o que publicar");
    }

    @Test
    void formatoRecusadoNaoTocaArmazenamentoNemBanco() {
        assertThrows(FormatoNaoSuportadoException.class,
                () -> useCase.executar(comando("relatorio.pdf", "application/pdf")));

        assertTrue(videos.armazenados.isEmpty());
        assertEquals(null, arquivos.ultimoArquivoGravado);
    }

    @Test
    void videoVazioERecusado() {
        var comando = new EnviarVideoUseCase.Command("ferias.mp4", "video/mp4", 0L, ARQUIVO, DONO);

        var falha = assertThrows(RuntimeException.class, () -> useCase.executar(comando));
        assertTrue(falha instanceof IllegalArgumentException || falha instanceof CompletionException);
    }

    private ReconciliarPublicacoesPendentesUseCase reconciliacao() {
        return new ReconciliarPublicacoesPendentesUseCase(videos,
                new PublicarExtrairVideo(arquivos, extracao, videos),
                new PublicarVideoFalhou(new GatewaysEmMemoria.NotificacaoEnvios(), videos));
    }

    /** O equivalente em memoria de esperar a folga contra crash da varredura passar. */
    private void envelhecerAlemDaFolgaDaVarredura(Video video) {
        videos.armazenados.put(video.id(), Video.reconstituir(
                video.id(), video.nome(), video.tamanhoBytes(), video.dono(), video.chaveVideo(),
                video.estado(), video.recebidoEm().minus(2, ChronoUnit.MINUTES), null, null, null, null, null));
    }

    private static EnviarVideoUseCase.Command comando(String nome, String contentType) {
        return new EnviarVideoUseCase.Command(nome, contentType, 1_024L, ARQUIVO, DONO);
    }
}
