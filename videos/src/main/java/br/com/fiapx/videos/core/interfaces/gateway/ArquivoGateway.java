package br.com.fiapx.videos.core.interfaces.gateway;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * O armazenamento de objetos visto pelo dominio. <b>Quem conhece a convencao de nomes de
 * chave e este gateway</b>, nao o core: o Video guarda a chave como string opaca, entao
 * mudar o formato nao toca entidade, esquema nem contrato (tickets 009 e 011).
 *
 * <p>Tipos do JDK puro de proposito. {@link Path} porque o upload ja pousou em disco pelo
 * Vert.x e o adapter o envia sem passar por memoria; {@link Flow.Publisher} porque o core
 * precisa declarar streaming sem importar Mutiny nem Vert.x.
 */
public interface ArquivoGateway {

    /**
     * Grava o Video enviado e <b>devolve a chave que gravou</b>, para o Video recebe-la
     * pronta. A falha, ja depois das repeticoes, chega como
     * {@link br.com.fiapx.videos.core.exceptions.ArmazenamentoIndisponivelException} (ticket 108).
     */
    CompletableFuture<String> gravarVideo(UUID idVideo, String nome, Path arquivo);

    /**
     * O Video chegou a um estado terminal, e so a partir daqui o original pode expirar
     * (ticket 105). Original sem esta marca <b>nunca</b> expira: e o que impede um Video preso
     * de perder o arquivo antes de alguem o resgatar. Idempotente.
     */
    CompletableFuture<Void> marcarDesfechoDoOriginal(String chaveVideo);

    /**
     * Apaga o original que ficou sem linha no banco. Existe so para a limpeza do envio que
     * falhou no {@code INSERT} (ticket 105); o caminho feliz nao apaga nada.
     */
    CompletableFuture<Void> apagarOriginal(String chaveVideo);

    /**
     * A chave onde o Pacote deste Video sera gravado. Quem a constroi e este gateway, e nao
     * o `extracao`, que recebe origem e destino prontos e nao conhece a convencao
     * (contrato de mensagens). Quem a <b>usa</b> para publicar o comando e o ticket 017.
     */
    String chaveDoPacote(UUID idVideo);

    /**
     * Abre o Pacote para leitura. {@link Optional#empty()} significa <b>o objeto nao esta
     * la</b> — e so isso, nunca "o armazenamento falhou". A distincao e o que separa o 410
     * (desista) do 500 (tente de novo): mapear qualquer falha do MinIO para 410 faria um
     * MinIO fora do ar mandar o cliente desistir para sempre (ticket 019).
     */
    CompletableFuture<Optional<Flow.Publisher<ByteBuffer>>> abrirPacote(String chavePacote);
}
