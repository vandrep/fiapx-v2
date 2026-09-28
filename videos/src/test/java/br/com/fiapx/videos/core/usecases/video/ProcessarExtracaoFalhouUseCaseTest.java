package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A guarda de unicidade do e-mail (ADR 0001): tres entregas do mesmo {@code ExtracaoFalhou}
 * devem produzir exatamente um {@code VideoFalhou}, porque so a transicao que de fato mudou
 * a linha publica.
 */
class ProcessarExtracaoFalhouUseCaseTest {

    private static final Dono DONO = new Dono("sub-1", "usuario@exemplo.com");

    private GatewaysEmMemoria.Videos videos;
    private GatewaysEmMemoria.NotificacaoEnvios notificacao;
    private GatewaysEmMemoria.Arquivos arquivos;
    private ProcessarExtracaoFalhouUseCase useCase;
    private Video video;

    @BeforeEach
    void montar() {
        videos = new GatewaysEmMemoria.Videos();
        notificacao = new GatewaysEmMemoria.NotificacaoEnvios();
        arquivos = new GatewaysEmMemoria.Arquivos();
        useCase = new ProcessarExtracaoFalhouUseCase(videos, arquivos, new PublicarVideoFalhou(notificacao, videos));

        video = Video.novo("ferias.mp4", 1_024L, DONO).armazenadoEm("id/original.mp4");
        video.marcaComoIniciada(Instant.now());
        videos.armazenados.put(video.id(), video);
    }

    @Test
    void tresEntregasDoMesmoEventoProduzemUmUnicoVideoFalhou() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();
        useCase.executar(comando).join();
        useCase.executar(comando).join();

        assertEquals(1, notificacao.idsEnviados.size());
        assertEquals(EstadoVideo.FALHOU, video.estado());
    }

    @Test
    void aFalhaQueChegaAntesDaIniciadaAindaFalhaENotificaUmaVezSo() {
        // Defeito 1 do ticket 027, do lado da falha: alargar o predecessor nao afrouxa a
        // guarda de unicidade — o UPDATE continua mudando a linha exatamente uma vez, saindo
        // de RECEBIDO em vez de PROCESSANDO.
        var recemRecebido = Video.novo("chegou-fora-de-ordem.mp4", 2_048L, DONO)
                .armazenadoEm("id/original.mp4");
        videos.armazenados.put(recemRecebido.id(), recemRecebido);
        assertEquals(EstadoVideo.RECEBIDO, recemRecebido.estado());
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                recemRecebido.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();
        useCase.executar(comando).join();

        assertEquals(EstadoVideo.FALHOU, recemRecebido.estado());
        assertEquals(1, notificacao.idsEnviados.size());
    }

    @Test
    void aPrimeiraEntregaMarcaAFalhaComoPublicada() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.FORMATO_NAO_SUPORTADO, Instant.now());

        useCase.executar(comando).join();

        assertNotNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void compareAndSwapQuePerdeACorridaNaoPublicaVideoFalhou() {
        // Outra entrega do mesmo evento moveu a linha para FALHOU entre o SELECT e o UPDATE
        // desta: o Video que este fluxo leu ainda diz PROCESSANDO, a transicao no dominio
        // passa, e quem reprova e o UPDATE condicional. O e-mail e da entrega que venceu.
        videos.outraEntregaVenceACorridaPara(video.id(), EstadoVideo.FALHOU);
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();

        assertEquals(0, notificacao.idsEnviados.size());
        assertNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void umEventoParaUmVideoQueNuncaExistiuNaoPublicaNada() {
        var comando = new ProcessarExtracaoFalhouUseCase.Command(
                java.util.UUID.randomUUID(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now());

        useCase.executar(comando).join();

        assertEquals(0, notificacao.idsEnviados.size());
    }

    @Test
    void oFalhouMarcaODesfechoDoOriginal() {
        useCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now())).join();

        assertEquals(List.of("id/original.mp4"), arquivos.originaisComDesfecho);
    }

    @Test
    void marcacaoQueFalhaNaoSeguraOFalhouNemOAviso() {
        arquivos.falhaAoMarcar = new IllegalStateException("MinIO fora");

        useCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now())).join();

        assertEquals(EstadoVideo.FALHOU, video.estado());
        assertEquals(List.of(video.id()), notificacao.idsEnviados);
        assertNotNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void oAvisoNaoEsperaAMarca() {
        // Com o MinIO fora, a marca gasta as repeticoes do ADR 0001. O aviso ao Dono sai antes.
        arquivos.marcaSegurada = new CompletableFuture<>();

        var processando = useCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now()));

        assertEquals(List.of(video.id()), notificacao.idsEnviados);
        arquivos.marcaSegurada.complete(null);
        processando.join();
    }

    @Test
    void avisoQueFalhaAindaMarcaODesfechoEVoltaAFila() {
        // O UPDATE ja gravou FALHOU: a reentrega nao marcaria de novo, entao a marca vem mesmo
        // assim. O aviso fica com a varredura do ADR 0003, e a falha continua subindo.
        notificacao.falhaNoEnvio = new IllegalStateException("broker fora");

        assertThrows(CompletionException.class, () -> useCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now())).join());

        assertEquals(List.of("id/original.mp4"), arquivos.originaisComDesfecho);
        assertNull(videos.falhaPublicadaEm.get(video.id()));
    }

    @Test
    void quemPerdeACorridaParaOutroTerminalAindaMarcaODesfecho() {
        videos.outraEntregaVenceACorridaPara(video.id(), EstadoVideo.CONCLUIDO);

        useCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                video.id(), MotivoFalha.ARQUIVO_INVALIDO, Instant.now())).join();

        assertEquals(List.of("id/original.mp4"), arquivos.originaisComDesfecho);
        assertEquals(List.of(), notificacao.idsEnviados);
    }
}
