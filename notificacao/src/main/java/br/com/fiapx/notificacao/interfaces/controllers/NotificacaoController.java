package br.com.fiapx.notificacao.interfaces.controllers;

import br.com.fiapx.notificacao.core.usecases.notificacao.EnviarNotificacaoDeFalhaUseCase;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Traduz mensagem em Command e chama o use case, sem regra propria (docs/contratos/
 * mensagens.md § Camadas). Analogo ao {@code ExtracaoController} do `extracao`: o consumidor
 * de mensageria e quem cumpre o papel de fronteira, ja que `notificacao` nao tem `Resource`.
 */
public class NotificacaoController {

    private final EnviarNotificacaoDeFalhaUseCase enviarNotificacaoDeFalhaUseCase;

    public NotificacaoController(EnviarNotificacaoDeFalhaUseCase enviarNotificacaoDeFalhaUseCase) {
        this.enviarNotificacaoDeFalhaUseCase = enviarNotificacaoDeFalhaUseCase;
    }

    public CompletableFuture<Void> notificarFalha(UUID idVideo, String emailDono, String nomeArquivoOriginal,
                                                  String codigoMotivo, Instant ocorridoEm) {
        return enviarNotificacaoDeFalhaUseCase.executar(new EnviarNotificacaoDeFalhaUseCase.Command(
                idVideo, emailDono, nomeArquivoOriginal, codigoMotivo, ocorridoEm));
    }
}
