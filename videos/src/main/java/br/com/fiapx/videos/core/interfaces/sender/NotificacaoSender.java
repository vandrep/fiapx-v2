package br.com.fiapx.videos.core.interfaces.sender;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.MotivoFalha;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publica o fato de que um Vídeo falhou definitivamente. `extracao` e `notificacao` nunca se
 * falam: e o `videos`, dono do estado, quem manda este evento — e so quando o `UPDATE` de
 * transicao de fato mudou a linha (ADR 0001, docs/contratos/mensagens.md).
 */
public interface NotificacaoSender {

    /**
     * @param nomeArquivoOriginal um e-mail que nao diz qual Vídeo falhou e inutil
     * @param motivo repassado do {@code ExtracaoFalhou} que causou a transicao
     */
    CompletableFuture<Void> enviarVideoFalhou(UUID idVideo,
                                              Dono dono,
                                              String nomeArquivoOriginal,
                                              MotivoFalha motivo,
                                              Instant ocorridoEm);
}
