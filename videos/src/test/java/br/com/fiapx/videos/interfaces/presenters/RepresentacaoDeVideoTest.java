package br.com.fiapx.videos.interfaces.presenters;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.interfaces.presenters.view_model.VideoViewModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * O contrato HTTP publica <b>uma</b> representacao de Video: o corpo do envio, o do GET
 * individual e cada item da listagem tem de sair identicos para o mesmo Video (ticket 047).
 *
 * <p>As esperas sao escritas a mao, campo a campo, em vez de sairem da mesma conversao que
 * esta sob julgamento — senao o teste concordaria com qualquer mapeamento errado.
 */
class RepresentacaoDeVideoTest {

    private static final UUID ID_CONCLUIDO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_FALHOU = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ID_RECEBIDO = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final Instant RECEBIDO_EM = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant FINALIZADO_EM = Instant.parse("2026-01-02T03:09:05Z");

    private static final VideoDTO CONCLUIDO = new VideoDTO(
            ID_CONCLUIDO, "ferias.mp4", EstadoVideo.CONCLUIDO, 1_024L, RECEBIDO_EM, FINALIZADO_EM, null);

    private static final VideoDTO FALHOU = new VideoDTO(
            ID_FALHOU, "quebrado.mkv", EstadoVideo.FALHOU, 7L, RECEBIDO_EM, FINALIZADO_EM,
            MotivoFalha.SEM_FLUXO_DE_VIDEO);

    private static final VideoDTO RECEBIDO = new VideoDTO(
            ID_RECEBIDO, "recem-chegado.mov", EstadoVideo.RECEBIDO, 42L, RECEBIDO_EM, null, null);

    @Test
    void osSeteCamposPublicosSaemComNomeTipoEValorDoVideo() {
        // finalizadoEm sai como concluidoEm e motivo sai como codigo: os dois sao contrato.
        assertEquals(
                new VideoViewModel(ID_FALHOU, "quebrado.mkv", EstadoVideo.FALHOU, 7L, RECEBIDO_EM,
                        FINALIZADO_EM, MotivoFalha.SEM_FLUXO_DE_VIDEO),
                representacaoIndividual(FALHOU));
    }

    @Test
    void campoNuloContinuaNuloEmVezDeVirarValorDefault() {
        VideoViewModel viewModel = representacaoIndividual(RECEBIDO);

        assertNull(viewModel.concluidoEm());
        assertNull(viewModel.motivo());
        assertEquals(
                new VideoViewModel(ID_RECEBIDO, "recem-chegado.mov", EstadoVideo.RECEBIDO, 42L,
                        RECEBIDO_EM, null, null),
                viewModel);
    }

    @Test
    void aListagemMontaCadaItemComAMesmaRepresentacaoDaConsultaIndividual() {
        List<VideoDTO> videos = List.of(RECEBIDO, CONCLUIDO, FALHOU);

        VideosPaginadosPresenterAdapter paginado = new VideosPaginadosPresenterAdapter();
        paginado.present(new Pagina<>(videos, 0, 20, 57L));

        assertEquals(videos.stream().map(RepresentacaoDeVideoTest::representacaoIndividual).toList(),
                paginado.viewModel().conteudo());
    }

    private static VideoViewModel representacaoIndividual(VideoDTO videoDTO) {
        VideoPresenterAdapter presenter = new VideoPresenterAdapter();
        presenter.present(videoDTO);
        return presenter.viewModel();
    }
}
