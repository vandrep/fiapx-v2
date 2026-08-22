package br.com.fiapx.videos.core.exceptions;

import br.com.fiapx.videos.core.entities.EstadoVideo;

/**
 * "Ainda nao": a Extracao nao terminou. Vira 409, que convida o cliente a repetir — ao
 * contrario do 410 de {@link PacoteExpiradoException}, que diz para parar.
 */
public class PacoteIndisponivelException extends RuntimeException {

    public PacoteIndisponivelException(EstadoVideo estado) {
        super("Pacote indisponível: o Vídeo está em " + estado + " e só CONCLUIDO tem Pacote");
    }
}
