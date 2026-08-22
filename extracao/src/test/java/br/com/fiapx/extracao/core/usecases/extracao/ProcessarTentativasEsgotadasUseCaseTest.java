package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessarTentativasEsgotadasUseCaseTest {

    @Test
    void publicaExtracaoFalhouComTentativasEsgotadas() throws Exception {
        var sender = new GatewaysEmMemoria.ExtracaoEventosSenderEmMemoria();
        var useCase = new ProcessarTentativasEsgotadasUseCase(sender);
        var idVideo = UUID.randomUUID();

        useCase.executar(new ProcessarTentativasEsgotadasUseCase.Command(idVideo, "3 entregas sem ack")).get();

        assertEquals(1, sender.falhas.size());
        var falha = sender.falhas.get(0);
        assertEquals(idVideo, falha.idVideo());
        assertEquals(MotivoFalha.TENTATIVAS_ESGOTADAS, falha.motivo());
        assertEquals("3 entregas sem ack", falha.detalheTecnico());
    }
}
