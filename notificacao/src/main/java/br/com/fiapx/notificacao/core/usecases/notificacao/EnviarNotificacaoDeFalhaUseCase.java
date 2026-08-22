package br.com.fiapx.notificacao.core.usecases.notificacao;

import br.com.fiapx.notificacao.core.entities.MotivoFalha;
import br.com.fiapx.notificacao.core.entities.NotificacaoDeFalha;
import br.com.fiapx.notificacao.core.interfaces.gateway.EmailGateway;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * O `notificacao` inteiro, ponta a ponta: traduz o codigo de motivo e manda o e-mail. Sem
 * estado proprio e sem deduplicacao — o e-mail e pelo menos uma vez, e a unicidade e
 * responsabilidade da transicao de estado em `videos` (ADR 0001). Qualquer falha do
 * {@link EmailGateway} propaga como excecao: e o consumidor quem da nack e deixa o
 * {@code x-delivery-limit} da fila quorum decidir.
 *
 * <p>{@code donoSub} do contrato nao entra aqui: e chave de suporte para o log do consumidor
 * (framework.dispatcher), o use case nao tem uso de negocio para ela.
 */
public class EnviarNotificacaoDeFalhaUseCase {

    private final EmailGateway emailGateway;

    public EnviarNotificacaoDeFalhaUseCase(EmailGateway emailGateway) {
        this.emailGateway = emailGateway;
    }

    public CompletableFuture<Void> executar(Command command) {
        var notificacao = new NotificacaoDeFalha(
                command.idVideo(), command.nomeArquivoOriginal(),
                MotivoFalha.doCodigo(command.codigoMotivo()), command.ocorridoEm());

        return emailGateway.enviar(command.emailDono(), notificacao.assunto(), notificacao.corpo());
    }

    public record Command(UUID idVideo, String emailDono, String nomeArquivoOriginal,
                          String codigoMotivo, Instant ocorridoEm) {
    }
}
