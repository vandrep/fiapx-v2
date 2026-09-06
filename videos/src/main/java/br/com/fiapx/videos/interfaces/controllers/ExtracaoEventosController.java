package br.com.fiapx.videos.interfaces.controllers;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoConcluidaUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoFalhouUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoIniciadaUseCase;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Traduz evento em Command e escolhe o use case. Analogo ao {@link VideosController} do lado
 * HTTP: nao conhece o record do contrato de mensagens, so os tipos do {@code core} que o
 * consumidor ja desmontou dele (docs/contratos/mensagens.md § Camadas).
 */
public class ExtracaoEventosController {

    private final ProcessarExtracaoIniciadaUseCase processarExtracaoIniciadaUseCase;
    private final ProcessarExtracaoConcluidaUseCase processarExtracaoConcluidaUseCase;
    private final ProcessarExtracaoFalhouUseCase processarExtracaoFalhouUseCase;

    public ExtracaoEventosController(ProcessarExtracaoIniciadaUseCase processarExtracaoIniciadaUseCase,
                                     ProcessarExtracaoConcluidaUseCase processarExtracaoConcluidaUseCase,
                                     ProcessarExtracaoFalhouUseCase processarExtracaoFalhouUseCase) {
        this.processarExtracaoIniciadaUseCase = processarExtracaoIniciadaUseCase;
        this.processarExtracaoConcluidaUseCase = processarExtracaoConcluidaUseCase;
        this.processarExtracaoFalhouUseCase = processarExtracaoFalhouUseCase;
    }

    public CompletableFuture<Void> processarIniciada(UUID idVideo) {
        return processarExtracaoIniciadaUseCase.executar(
                new ProcessarExtracaoIniciadaUseCase.Command(idVideo));
    }

    public CompletableFuture<Void> processarConcluida(UUID idVideo, ResultadoExtracao resultado) {
        return processarExtracaoConcluidaUseCase.executar(
                new ProcessarExtracaoConcluidaUseCase.Command(idVideo, resultado));
    }

    public CompletableFuture<Void> processarFalhou(UUID idVideo, String codigoMotivo, Instant ocorridoEm) {
        return processarExtracaoFalhouUseCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                idVideo, MotivoFalha.doCodigo(codigoMotivo), ocorridoEm));
    }
}
