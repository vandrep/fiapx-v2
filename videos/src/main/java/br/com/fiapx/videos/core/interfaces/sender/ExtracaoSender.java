package br.com.fiapx.videos.core.interfaces.sender;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica o comando que manda o {@code extracao} trabalhar. O record {@code ExtrairVideo}
 * do contrato de mensagens nao cruza para o core: esta interface fala em tipos de dominio, e
 * quem monta a mensagem e o adapter em {@code framework.dispatcher} (docs/contratos/mensagens.md).
 */
public interface ExtracaoSender {

    /**
     * @param chaveVideo onde o `extracao` le o Vídeo no MinIO
     * @param chaveDestinoPacote onde o `extracao` deve gravar o Pacote; construida pelo
     *                           {@code ArquivoGateway}, que e quem conhece a convencao de
     *                           chave (ticket 011)
     */
    CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote);
}
