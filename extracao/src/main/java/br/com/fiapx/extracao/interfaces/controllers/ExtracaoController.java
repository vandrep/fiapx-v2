package br.com.fiapx.extracao.interfaces.controllers;

import br.com.fiapx.extracao.core.usecases.extracao.ProcessarExtracaoUseCase;
import br.com.fiapx.extracao.core.usecases.extracao.ProcessarTentativasEsgotadasUseCase;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Traduz mensagem em Command e escolhe o use case. Analogo ao {@code ExtracaoEventosController}
 * do `videos`: nao conhece o record do contrato, so os campos ja desmontados
 * (docs/contratos/mensagens.md § Camadas).
 */
public class ExtracaoController {

    private final ProcessarExtracaoUseCase processarExtracaoUseCase;
    private final ProcessarTentativasEsgotadasUseCase processarTentativasEsgotadasUseCase;

    public ExtracaoController(ProcessarExtracaoUseCase processarExtracaoUseCase,
                              ProcessarTentativasEsgotadasUseCase processarTentativasEsgotadasUseCase) {
        this.processarExtracaoUseCase = processarExtracaoUseCase;
        this.processarTentativasEsgotadasUseCase = processarTentativasEsgotadasUseCase;
    }

    public CompletableFuture<Void> processarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
        return processarExtracaoUseCase.executar(
                new ProcessarExtracaoUseCase.Command(idVideo, chaveVideo, chaveDestinoPacote));
    }

    public CompletableFuture<Void> processarTentativasEsgotadas(UUID idVideo, String detalheTecnico) {
        return processarTentativasEsgotadasUseCase.executar(
                new ProcessarTentativasEsgotadasUseCase.Command(idVideo, detalheTecnico));
    }
}
