package br.com.fiapx.extracao.framework.dispatcher;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento {@code extracao} -> {@code videos}. {@code detalheTecnico} e so para log — exit code
 * e trecho do stderr, nunca chega ao usuario e nunca entra no {@code core}
 * (docs/contratos/mensagens.md).
 */
public record ExtracaoFalhou(UUID idVideo, String codigoMotivo, String detalheTecnico, Instant ocorridoEm) {
}
