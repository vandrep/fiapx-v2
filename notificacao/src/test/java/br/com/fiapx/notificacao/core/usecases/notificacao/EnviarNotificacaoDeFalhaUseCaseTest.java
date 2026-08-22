package br.com.fiapx.notificacao.core.usecases.notificacao;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnviarNotificacaoDeFalhaUseCaseTest {

    @Test
    void enviaParaOEmailDoDonoComOMotivoTraduzido() throws Exception {
        var gateway = new EmailGatewayEmMemoria();
        var useCase = new EnviarNotificacaoDeFalhaUseCase(gateway);
        var idVideo = UUID.randomUUID();

        useCase.executar(new EnviarNotificacaoDeFalhaUseCase.Command(
                idVideo, "dono@example.com", "ferias.mp4", "FORMATO_NAO_SUPORTADO", Instant.parse("2026-08-22T12:00:00Z")))
                .get();

        assertEquals(1, gateway.enviados.size());
        var enviado = gateway.enviados.get(0);
        assertEquals("dono@example.com", enviado.destinatario());
        assertTrue(enviado.assunto().contains("ferias.mp4"));
        assertTrue(enviado.corpo().contains("não é suportado"));
        assertTrue(enviado.corpo().contains(idVideo.toString()));
    }

    @Test
    void codigoDesconhecidoAindaAssimEnviaComFraseGenerica() throws Exception {
        var gateway = new EmailGatewayEmMemoria();
        var useCase = new EnviarNotificacaoDeFalhaUseCase(gateway);

        useCase.executar(new EnviarNotificacaoDeFalhaUseCase.Command(
                UUID.randomUUID(), "dono@example.com", "video.mp4", "CODIGO_QUE_NAO_EXISTE", Instant.now()))
                .get();

        assertEquals(1, gateway.enviados.size());
        assertTrue(gateway.enviados.get(0).corpo().contains("não foi possível determinar"));
    }

    @Test
    void falhaDoGatewayPropagaParaOConsumidorDarNack() {
        var gateway = new EmailGatewayEmMemoria();
        gateway.falha = new RuntimeException("SMTP fora do ar");
        var useCase = new EnviarNotificacaoDeFalhaUseCase(gateway);

        var futuro = useCase.executar(new EnviarNotificacaoDeFalhaUseCase.Command(
                UUID.randomUUID(), "dono@example.com", "video.mp4", "ARQUIVO_INVALIDO", Instant.now()));

        var falha = assertThrows(ExecutionException.class, futuro::get);
        assertEquals("SMTP fora do ar", falha.getCause().getMessage());
    }
}
