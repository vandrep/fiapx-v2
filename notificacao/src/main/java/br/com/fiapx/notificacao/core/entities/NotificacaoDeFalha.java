package br.com.fiapx.notificacao.core.entities;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * O e-mail de falha visto pelo dominio: o unico lugar do sistema que decide assunto e corpo
 * (docs/contratos/mensagens.md § VideoFalhou). {@code idVideo} entra no corpo como referencia
 * de suporte — o `donoSub` do contrato fica so em log (adapter), porque um identificador OIDC
 * nao diz nada ao usuario.
 */
public record NotificacaoDeFalha(UUID idVideo, String nomeArquivoOriginal, MotivoFalha motivo, Instant ocorridoEm) {

    private static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);

    public String assunto() {
        return "Falha ao processar o vídeo \"" + nomeArquivoOriginal + "\"";
    }

    public String corpo() {
        return """
                Olá,

                Não foi possível concluir o processamento do vídeo "%s".

                Motivo: %s

                Ocorrido em: %s UTC
                Referência: %s

                Você pode enviar o vídeo novamente. Se o problema persistir, guarde a \
                referência acima para o suporte.
                """.formatted(nomeArquivoOriginal, motivo.paraFrase(), FORMATO_DATA.format(ocorridoEm), idVideo);
    }
}
