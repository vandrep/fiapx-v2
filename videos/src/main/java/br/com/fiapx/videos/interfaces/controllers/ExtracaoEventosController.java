package br.com.fiapx.videos.interfaces.controllers;

import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoConcluidaUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoFalhouUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoIniciadaUseCase;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Traduz evento em Command e escolhe o use case. Analogo ao {@link VideosController} do lado
 * HTTP: nao conhece o record do contrato de mensagens, so os campos ja desmontados
 * (docs/contratos/mensagens.md § Camadas).
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

    public CompletableFuture<Void> processarIniciada(UUID idVideo, Instant iniciadaEm) {
        return processarExtracaoIniciadaUseCase.executar(
                new ProcessarExtracaoIniciadaUseCase.Command(idVideo, iniciadaEm));
    }

    public CompletableFuture<Void> processarConcluida(UUID idVideo,
                                                      String chavePacote,
                                                      int quantidadeFrames,
                                                      long tamanhoBytes,
                                                      Instant concluidaEm) {
        return processarExtracaoConcluidaUseCase.executar(new ProcessarExtracaoConcluidaUseCase.Command(
                idVideo, concluidaEm, chavePacote, quantidadeFrames, tamanhoBytes));
    }

    public CompletableFuture<Void> processarFalhou(UUID idVideo, String codigoMotivo, Instant ocorridoEm) {
        return processarExtracaoFalhouUseCase.executar(new ProcessarExtracaoFalhouUseCase.Command(
                idVideo, MotivoFalha.doCodigo(codigoMotivo), ocorridoEm));
    }
}
