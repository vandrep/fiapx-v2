package br.com.fiapx.extracao.core.interfaces.gateway;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * O armazenamento de objetos visto pelo dominio. Ao contrario do {@code ArquivoGateway} do
 * `videos`, este nao conhece nenhuma convencao de chave: chaves chegam prontas na mensagem
 * {@code ExtrairVideo} (docs/contratos/mensagens.md) — o `extracao` so le e grava onde
 * mandarem.
 *
 * <p>Tipos do JDK puro de proposito, como no `videos`: {@link Path} porque o pipeline de
 * ffmpeg precisa de arquivos reais em disco (scratch, ticket 011), nao de bytes em memoria.
 */
public interface ArquivoGateway {

    /**
     * Baixa o Video da chave dada para dentro de {@code diretorio}, que ja existe (preparado
     * pelo {@link EspacoDeTrabalhoGateway}). Devolve o caminho local do arquivo baixado.
     */
    CompletableFuture<Path> baixarVideo(Path diretorio, String chaveVideo);

    /** Sobe o Pacote local para a chave de destino que a mensagem trouxe. */
    CompletableFuture<Void> gravarPacote(String chaveDestinoPacote, Path pacoteLocal);
}
