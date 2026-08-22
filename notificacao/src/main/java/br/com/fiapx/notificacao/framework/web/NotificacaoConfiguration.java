package br.com.fiapx.notificacao.framework.web;

import br.com.fiapx.notificacao.core.interfaces.gateway.EmailGateway;
import br.com.fiapx.notificacao.core.usecases.notificacao.EnviarNotificacaoDeFalhaUseCase;
import br.com.fiapx.notificacao.interfaces.controllers.NotificacaoController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * O unico lugar que conhece o grafo de objetos: os use cases sao POJOs sem anotacao de CDI,
 * e e aqui que eles recebem os gateways (mesmo papel do {@code ExtracaoConfiguration}).
 */
@ApplicationScoped
public class NotificacaoConfiguration {

    @Produces
    NotificacaoController notificacaoController(EmailGateway emailGateway) {
        return new NotificacaoController(new EnviarNotificacaoDeFalhaUseCase(emailGateway));
    }
}
