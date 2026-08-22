package br.com.fiapx.extracao.framework.web;

import br.com.fiapx.extracao.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.EspacoDeTrabalhoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.ExtracaoDeFramesGateway;
import br.com.fiapx.extracao.core.interfaces.sender.ExtracaoEventosSender;
import br.com.fiapx.extracao.core.usecases.extracao.ProcessarExtracaoUseCase;
import br.com.fiapx.extracao.core.usecases.extracao.ProcessarTentativasEsgotadasUseCase;
import br.com.fiapx.extracao.interfaces.controllers.ExtracaoController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * O unico lugar que conhece o grafo de objetos: os use cases sao POJOs sem anotacao de CDI,
 * e e aqui que eles recebem gateways e sender (mesmo papel do {@code VideosConfiguration}).
 */
@ApplicationScoped
public class ExtracaoConfiguration {

    /** Teto de duracao do ticket 011: acima disso, DURACAO_EXCEDIDA, falha permanente. */
    @ConfigProperty(name = "fiapx.extracao.teto-duracao-minutos", defaultValue = "20")
    long tetoDuracaoMinutos;

    @Produces
    ExtracaoController extracaoController(ArquivoGateway arquivoGateway,
                                          ExtracaoDeFramesGateway extracaoDeFramesGateway,
                                          EspacoDeTrabalhoGateway espacoDeTrabalhoGateway,
                                          ExtracaoEventosSender extracaoEventosSender) {
        return new ExtracaoController(
                new ProcessarExtracaoUseCase(arquivoGateway, extracaoDeFramesGateway, espacoDeTrabalhoGateway,
                        extracaoEventosSender, Duration.ofMinutes(tetoDuracaoMinutos)),
                new ProcessarTentativasEsgotadasUseCase(extracaoEventosSender));
    }
}
