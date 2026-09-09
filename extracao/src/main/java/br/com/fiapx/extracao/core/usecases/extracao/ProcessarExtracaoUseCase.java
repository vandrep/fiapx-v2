package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.ResultadoExtracao;
import br.com.fiapx.extracao.core.exceptions.FalhaAoPublicarExtracaoFalhouException;
import br.com.fiapx.extracao.core.exceptions.FalhaPermanenteDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.EspacoDeTrabalhoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.ExtracaoDeFramesGateway;
import br.com.fiapx.extracao.core.interfaces.sender.ExtracaoEventosSender;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * O worker inteiro, ponta a ponta: baixa o Video, extrai frames, empacota, sobe o Pacote, e
 * publica os tres eventos do contrato (docs/contratos/mensagens.md). Analogo ao
 * {@code EnviarVideoUseCase} do `videos`, mas sem estado proprio — `extracao` nao tem banco
 * (AGENTS.md).
 *
 * <p>Falha permanente ({@link FalhaPermanenteDeExtracaoException}) publica {@code
 * ExtracaoFalhou} e o metodo completa <b>normalmente</b> — e o que faz o consumidor dar ack
 * sem gastar o {@code x-delivery-limit}. Qualquer outra falha propaga como excecao, e e o
 * consumidor quem da nack (ADR 0001).
 * Se a publicacao da propria falha permanente falhar, a classificacao e preservada em
 * {@link FalhaAoPublicarExtracaoFalhouException}; a borda precisa distingui-la de uma falha
 * transitoria do trabalho para nao executar a mesma Extracao para sempre (ticket 029).
 *
 * <p>O diretorio de trabalho e sempre limpo, sucesso ou falha (ticket 011): o worker morre
 * no meio por desenho, entao a limpeza mora aqui, nao num {@code finally} do chamador que um
 * crash simplesmente nao executaria. O que se limpa e o diretorio <b>desta tentativa</b> — o
 * que {@code prepararNovo} devolveu, e nao "o diretorio do Video" (ticket 041): duas replicas
 * podem estar processando o mesmo comando duplicado, e a limpeza de uma nao pode alcancar o
 * trabalho da outra.
 */
public class ProcessarExtracaoUseCase {

    private final ArquivoGateway arquivoGateway;
    private final ExtracaoDeFramesGateway extracaoDeFramesGateway;
    private final EspacoDeTrabalhoGateway espacoDeTrabalhoGateway;
    private final ExtracaoEventosSender extracaoEventosSender;
    private final Duration tetoDuracao;

    public ProcessarExtracaoUseCase(ArquivoGateway arquivoGateway,
                                    ExtracaoDeFramesGateway extracaoDeFramesGateway,
                                    EspacoDeTrabalhoGateway espacoDeTrabalhoGateway,
                                    ExtracaoEventosSender extracaoEventosSender,
                                    Duration tetoDuracao) {
        this.arquivoGateway = arquivoGateway;
        this.extracaoDeFramesGateway = extracaoDeFramesGateway;
        this.espacoDeTrabalhoGateway = espacoDeTrabalhoGateway;
        this.extracaoEventosSender = extracaoEventosSender;
        this.tetoDuracao = tetoDuracao;
    }

    public CompletableFuture<Void> executar(Command command) {
        var idVideo = command.idVideo();
        return extracaoEventosSender.enviarIniciada(idVideo, Instant.now())
                .thenCompose(ignorado -> espacoDeTrabalhoGateway.prepararNovo(idVideo))
                .thenCompose(diretorio -> extrairEPublicar(command, diretorio)
                        .handle((ignoradoResultado, erro) -> erro)
                        .thenCompose(erro -> espacoDeTrabalhoGateway.limpar(diretorio)
                                .thenApply(ignoradoLimpeza -> erro)))
                .thenCompose(erro -> erro == null
                        ? CompletableFuture.completedFuture((Void) null)
                        : tratarFalha(idVideo, erro));
    }

    private CompletableFuture<Void> extrairEPublicar(Command command, Path diretorio) {
        var destinoZip = diretorio.resolve("pacote.zip");
        return arquivoGateway.baixarVideo(diretorio, command.chaveVideo())
                .thenCompose(video ->
                        extracaoDeFramesGateway.processar(video, diretorio, destinoZip, tetoDuracao))
                .thenCompose(resultado -> arquivoGateway.gravarPacote(command.chaveDestinoPacote(), destinoZip)
                        .thenCompose(ignorado -> publicarConcluida(command, resultado)));
    }

    private CompletableFuture<Void> publicarConcluida(Command command, ResultadoExtracao resultado) {
        return extracaoEventosSender.enviarConcluida(command.idVideo(), command.chaveDestinoPacote(),
                resultado.quantidadeFrames(), resultado.tamanhoBytes(), Instant.now());
    }

    private CompletableFuture<Void> tratarFalha(UUID idVideo, Throwable erro) {
        var causa = causaRaiz(erro);
        if (causa instanceof FalhaPermanenteDeExtracaoException falha) {
            return extracaoEventosSender.enviarFalhou(idVideo, falha.motivo(), falha.detalheTecnico(), Instant.now())
                    .exceptionallyCompose(falhaDePublicacao -> CompletableFuture.failedFuture(
                            new FalhaAoPublicarExtracaoFalhouException(causaRaiz(falhaDePublicacao))));
        }
        return CompletableFuture.failedFuture(causa);
    }

    /**
     * Tira o envelope que o {@code CompletionStage} poe por cima da falha de verdade, e so ele:
     * um nivel, e nao a cadeia inteira. Quem decide o motivo da Extracao e quem decide se o nack
     * requeue precisam da falha que o adapter lancou, e nao do {@code CompletionException} que a
     * composicao acrescentou.
     *
     * <p>Estava escrito duas vezes nesta classe ate o ticket 087. Ele <b>nao</b> e a mesma coisa
     * que o percurso de causas do {@code ExtrairVideoConsumer} nem que o {@code desembrulhar} do
     * `videos`; o porque de os tres nao convergirem esta no `AGENTS.md`
     * § <i>As copias deliberadas entre servicos</i>.
     */
    private Throwable causaRaiz(Throwable falha) {
        return falha instanceof CompletionException ? falha.getCause() : falha;
    }

    public record Command(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
    }
}
