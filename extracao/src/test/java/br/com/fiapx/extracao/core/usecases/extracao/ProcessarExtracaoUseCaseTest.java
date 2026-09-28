package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.exceptions.FalhaAoPublicarExtracaoFalhouException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // O espaco limpo e o que foi preparado para esta tentativa, e nao um caminho derivado
        // do id do Video: e o que impede uma replica de apagar o trabalho da outra (041).
        assertEquals(espacoDeTrabalhoGateway.espacosCriados.get(0), espacoDeTrabalhoGateway.limpos.get(0));
    }

    /**
     * Duas execucoes do mesmo comando — o duplicado que o ADR 0003 tolera — nao podem
     * compartilhar espaco nem limpar o espaco uma da outra.
     */
    @Test
    void duasExecucoesDoMesmoVideoLimpamCadaUmaOSeuEspaco() throws Exception {
        var comando = new ProcessarExtracaoUseCase.Command(ID_VIDEO, "videos/chave.mp4", "pacotes/chave.zip");

        useCase.executar(comando).get();
        useCase.executar(comando).get();

        // Que dois `prepararNovo` devolvam caminhos distintos e assunto do adapter, e esta
        // provado la (EspacoDeTrabalhoAdapterTest); aqui o que se cobra e do use case: cada
        // execucao limpa o espaco que ela mesma preparou, na ordem em que preparou.
        assertEquals(2, espacoDeTrabalhoGateway.espacosCriados.size());
        assertEquals(espacoDeTrabalhoGateway.espacosCriados, espacoDeTrabalhoGateway.limpos);
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
    void falhaAoPublicarFalhaPermanentePreservaAClassificacaoParaNaoRecircular() {
        extracaoDeFramesGateway.falha = () -> GatewaysEmMemoria.falhaPermanente(MotivoFalha.ARQUIVO_INVALIDO);
        var falhaDePublicacao = new IllegalStateException("exchange recusou a publicacao");
        sender.falhaAoEnviarFalhou = falhaDePublicacao;

        var excecao = assertThrows(ExecutionException.class,
                () -> useCase.executar(new ProcessarExtracaoUseCase.Command(ID_VIDEO, "v", "p")).get());

        var falhaClassificada = assertInstanceOf(FalhaAoPublicarExtracaoFalhouException.class,
                excecao.getCause());
        assertSame(falhaDePublicacao, falhaClassificada.getCause());
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
