package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.exceptions.FormatoNaoSuportadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnviarVideoUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");
    private static final Path ARQUIVO = Path.of("/tmp/upload-123");

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
                new PublicarExtrairVideo(arquivos, extracao, videos), presenter);
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

    private static EnviarVideoUseCase.Command comando(String nome, String contentType) {
        return new EnviarVideoUseCase.Command(nome, contentType, 1_024L, ARQUIVO, DONO);
    }
}
