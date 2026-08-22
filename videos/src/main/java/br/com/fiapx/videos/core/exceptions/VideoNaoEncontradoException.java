package br.com.fiapx.videos.core.exceptions;

import java.util.UUID;

/**
 * Video inexistente <b>ou de outro usuario</b> — a borda nao distingue os dois casos, e e
 * exatamente esse o ponto: 403 confirmaria que aquele id existe.
 */
public class VideoNaoEncontradoException extends RuntimeException {

    public VideoNaoEncontradoException(UUID id) {
        super("Vídeo não encontrado: " + id);
    }
}
