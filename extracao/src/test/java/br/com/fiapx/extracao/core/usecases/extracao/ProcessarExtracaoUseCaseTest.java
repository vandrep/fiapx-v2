package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessarExtracaoUseCaseTest {

    private static final UUID ID_VIDEO = UUID.randomUUID();
    private static final Duration TETO_DURACAO = Duration.ofMinutes(20);

    private GatewaysEmMemoria.ArquivoGatewayEmMemoria arquivoGateway;
    private GatewaysEmMemoria.ExtracaoDeFramesGatewayEmMemoria extracaoDeFramesGateway;
    private GatewaysEmMemoria.EspacoDeTrabalhoGatewayEmMemoria espacoDeTrabalhoGateway;
    private GatewaysEmMemoria.ExtracaoEventosSenderEmMemoria sender;
    private ProcessarExtracaoUseCase useCase;

    @BeforeEach
    void montarDependencias() {
        arquivoGateway = new GatewaysEmMemoria.ArquivoGatewayEmMemoria();
        extracaoDeFramesGateway = new GatewaysEmMemoria.ExtracaoDeFramesGatewayEmMemoria();
        espacoDeTrabalhoGateway = new GatewaysEmMemoria.EspacoDeTrabalhoGatewayEmMemoria();
        sender = new GatewaysEmMemoria.ExtracaoEventosSenderEmMemoria();
        useCase = new ProcessarExtracaoUseCase(
                arquivoGateway, extracaoDeFramesGateway, espacoDeTrabalhoGateway, sender, TETO_DURACAO);
    }

    @Test
    void sucessoBaixaProcessaGravaEPublicaIniciadaEConcluida() throws Exception {
        var comando = new ProcessarExtracaoUseCase.Command(ID_VIDEO, "videos/chave.mp4", "pacotes/chave.zip");

        useCase.executar(comando).get();

        assertEquals(1, sender.iniciadas.size());
        assertEquals(ID_VIDEO, sender.iniciadas.get(0));
        assertEquals(1, sender.concluidas.size());
        var concluida = sender.concluidas.get(0);
        assertEquals(ID_VIDEO, concluida.idVideo());
        assertEquals("pacotes/chave.zip", concluida.chavePacote());
        assertEquals(42, concluida.quantidadeFrames());
        assertEquals(1024L, concluida.tamanhoBytes());
        assertTrue(sender.falhas.isEmpty());

        assertEquals("videos/chave.mp4", arquivoGateway.chavesBaixadas.get(0));
        assertEquals("pacotes/chave.zip", arquivoGateway.chavesGravadas.get(0));
    }

    @Test
    void prepararEspacoDeTrabalhoAntesDeBaixarELimparAoFim() throws Exception {
        var comando = new ProcessarExtracaoUseCase.Command(ID_VIDEO, "videos/chave.mp4", "pacotes/chave.zip");

        useCase.executar(comando).get();

        assertEquals(1, espacoDeTrabalhoGateway.preparados.size());
        assertEquals(ID_VIDEO, espacoDeTrabalhoGateway.preparados.get(0));
        assertEquals(1, espacoDeTrabalhoGateway.limpos.size());
        assertEquals(ID_VIDEO, espacoDeTrabalhoGateway.limpos.get(0));
    }

    @Test
    void tetoDeDuracaoConfiguradoChegaAoGatewayDeExtracao() throws Exception {
        useCase.executar(new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p")).get();

        assertEquals(TETO_DURACAO, extracaoDeFramesGateway.tetoRecebido);
    }

    @Test
    void falhaPermanentePublicaExtracaoFalhouECompletaNormalmenteParaDarAck() throws Exception {
        extracaoDeFramesGateway.falha = () -> GatewaysEmMemoria.falhaPermanente(MotivoFalha.ARQUIVO_INVALIDO);
        var comando = new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p");

        // Nao lanca: falha permanente e resultado esperado, nao excecao do use case.
        useCase.executar(comando).get();

        assertEquals(1, sender.falhas.size());
        assertEquals(MotivoFalha.ARQUIVO_INVALIDO, sender.falhas.get(0).motivo());
        assertTrue(sender.concluidas.isEmpty());
    }

    @Test
    void falhaPermanenteAindaAssimLimpaOEspacoDeTrabalho() throws Exception {
        extracaoDeFramesGateway.falha = () -> GatewaysEmMemoria.falhaPermanente(MotivoFalha.DURACAO_EXCEDIDA);

        useCase.executar(new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p")).get();

        assertEquals(1, espacoDeTrabalhoGateway.limpos.size());
    }

    @Test
    void falhaTransitoriaPropagaParaOChamadorDarNackENaoPublicaFalhou() {
        extracaoDeFramesGateway.falha = GatewaysEmMemoria::falhaTransitoria;
        var comando = new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p");

        var excecao = assertThrows(ExecutionException.class, () -> useCase.executar(comando).get());

        assertTrue(sender.falhas.isEmpty());
        assertTrue(excecao.getCause().getMessage().contains("falha de teste")
                || excecao.getCause() instanceof CompletionException);
    }

    @Test
    void falhaTransitoriaAindaAssimLimpaOEspacoDeTrabalho() {
        arquivoGateway.falhaAoGravar = new RuntimeException("MinIO fora do ar");

        assertThrows(ExecutionException.class,
                () -> useCase.executar(new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p")).get());

        assertEquals(1, espacoDeTrabalhoGateway.limpos.size());
    }
}
