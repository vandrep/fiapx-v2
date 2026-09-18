package br.com.fiapx.extracao.framework.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.FileTransformerConfiguration;
import software.amazon.awssdk.core.FileTransformerConfiguration.FailureBehavior;
import software.amazon.awssdk.core.FileTransformerConfiguration.FileWriteOption;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/**
 * As duas idas ao MinIO com a retentativa do ADR 0001, isoladas do
 * {@link ArquivoMinioAdapter}: aqui mora a chamada ao armazenamento e a politica de
 * reentrega dela, e la mora a traducao para o que o `core` pede.
 *
 * <h2>A retentativa e do Mutiny, e nao do {@code @Retry} (ticket 061)</h2>
 *
 * Ate o ticket 061 os dois metodos eram {@code @Retry} + {@code @AsynchronousNonBlocking} do
 * SmallRye Fault Tolerance. Isso <b>travava a Extracao para sempre</b>, em cerca de uma
 * mensagem a cada quinze, e o travamento foi medido, isolado e reproduzido — nao deduzido:
 *
 * <ol>
 * <li>numa operacao verdadeiramente assincrona, o interceptor do Fault Tolerance monta
 *     {@code RememberEventLoop -> ThreadOffload}. O {@code RememberEventLoop} le o contexto
 *     Vert.x corrente e o guarda no contexto da chamada; o {@code ThreadOffload}, vendo esse
 *     {@code Executor}, deixa de invocar o metodo na thread do chamador e o <b>reagenda no
 *     mesmo contexto Vert.x</b> em que a cadeia do consumidor ja esta rodando
 *     ({@code VertxExecutor} -> {@code runOnContext}/{@code executeBlocking});</li>
 * <li>esse reagendamento, as vezes, <b>nao roda nunca</b>. O corpo do metodo abaixo nem
 *     comeca, nenhuma thread trabalha, nenhum socket abre para o MinIO, nenhuma retentativa
 *     dispara e nada e logado. A mensagem fica sem ack, e com
 *     {@code max-outstanding-messages=1} a replica fica presa para sempre.</li>
 * </ol>
 *
 * <p>O que fica medido e (1) e (2); o passo que liga um ao outro e leitura, e a unica que
 * encaixa: na ordenacao daquele contexto a chamada entra atras da propria cadeia que espera
 * por ela — o consumidor {@code @Blocking} de {@code extracao.extrair} so termina quando este
 * download terminar. Nao da para observa-lo direto: qualquer log dentro dessa janela faz o
 * defeito sumir (90 ciclos limpos em tres variantes instrumentadas). Por isso a confirmacao
 * veio de A/B causal.
 *
 * <p>A medicao esta no ticket 061: com as anotacoes, 4 travamentos em ~60 ciclos; com a
 * retentativa do Mutiny, 0 em 90 ciclos, no mesmo host e com o mesmo roteiro
 * ({@code scripts/carga/travamento.sh}).
 *
 * <p>O que a troca preserva esta em {@link #comRepeticao}, numero por numero. O que ela
 * remove e o desvio pelo contexto Vert.x: o {@code Uni} repete na propria cadeia, sem
 * reagendar nada em fila de ninguem.
 *
 * <p>O que o download faz com o arquivo local entre uma chamada e outra — posse do destino,
 * descarte do parcial e o que acontece quando o descarte falha — esta em {@link #baixar}
 * (ticket 102).
 *
 * <p>O motivo original de este bean ser separado do adapter — self-invocation nao dispara
 * interceptor de CDI — morreu junto com o interceptor. A separacao fica porque continua
 * valendo por si: o adapter conhece bucket e chave, este bean conhece a API S3 e quantas
 * vezes insistir nela.
 */
@ApplicationScoped
public class ArquivoMinioClient {

    /**
     * Duas repeticoes depois da primeira chamada, que sao as 3 chamadas ao recurso do
     * ADR 0001 — a aritmetica esta em {@link #comRepeticao} (ticket 086).
     */
    private static final int MAXIMO_DE_REPETICOES = 2;
    /**
     * Fracao da espera, e nao valor absoluto: o Mutiny pede fracao. Os 10% vieram dos 200 ms de
     * jitter que o {@code @Retry} do MicroProfile trazia por default sobre 2 s, e continuam
     * amarrados a {@link #esperaEntreRepeticoes} — sobre a espera de producao dao os mesmos
     * 200 ms; sobre uma espera reduzida dao proporcionalmente menos.
     */
    private static final double JITTER = 0.1;
    /**
     * O destino ja foi criado por {@link Files#createFile} na mesma chamada, entao
     * {@code CREATE_OR_REPLACE_EXISTING} escreve sobre o caminho que esta operacao acabou de
     * tomar. {@code LEAVE} existe por causa da falha na limpeza, e nao da posse: a exclusao do SDK
     * so loga quando falha, e a desta classe precisa interromper o download. Deixar as duas ligadas
     * seria limpar duas vezes. Ver {@link #baixar}.
     */
    private static final FileTransformerConfiguration SOBRE_O_PROPRIO_ARQUIVO = FileTransformerConfiguration.builder()
            .fileWriteOption(FileWriteOption.CREATE_OR_REPLACE_EXISTING)
            .failureBehavior(FailureBehavior.LEAVE)
            .build();

    @Inject
    S3AsyncClient s3;

    /**
     * Os 2 s do ADR 0001 em producao, no mesmo desenho que o `videos` estreou no ticket 080. E
     * configuracao <b>deste bean</b>, no namespace {@code fiapx.} do projeto — nao chave de
     * tolerancia a falhas por interceptor, que a setima regra do teste arquitetural proibe desde o
     * ticket 064. O default vive aqui, e nao no {@code .properties}, para que a espera de producao
     * sobreviva a um arquivo de configuracao incompleto.
     *
     * <p>Nao ha {@code %test.} para esta chave no {@code application.properties}, e a diferenca e
     * do teste e nao da copia: quem paga a espera aqui e o {@code RepeticaoNoMinioTest}, que monta
     * o bean a mao e por isso atribui o campo direto. Nenhum {@code @QuarkusTest} deste servico
     * injeta blip no MinIO, entao um override de perfil nao teria leitor (ticket 085).
     */
    @ConfigProperty(name = "fiapx.armazenamento.espera-entre-repeticoes", defaultValue = "2s")
    Duration esperaEntreRepeticoes;

    /**
     * Cada chamada ao recurso baixa o objeto inteiro de novo, do zero, sobre um arquivo que ela
     * mesma acabou de criar; nao ha retomada por posicao (ticket 102).
     *
     * <p><b>O que o SDK ja fazia, medido no 2.41.18.</b> O {@code toFile(destino)} padrao abre com
     * {@code CREATE_NEW} e, em qualquer falha, apaga o destino. Um blip depois de bytes em disco
     * deixava o caminho livre, e a repeticao seguinte baixava de novo sem problema — o comentario
     * antigo, que dizia que ela queimava as repeticoes com {@code FileAlreadyExistsException},
     * estava errado. Dois buracos continuavam abertos, e sao eles que este metodo fecha:
     *
     * <ol>
     * <li><b>posse.</b> A exclusao do SDK nao olha quem criou o arquivo: se a abertura falha porque
     *     o destino ja existia, ele apaga o arquivo alheio. Aqui a posse e tomada por
     *     {@link Files#createFile}, que e atomico ({@code O_EXCL}) — nao um teste de existencia
     *     seguido de abertura. Colisao falha na hora, sem repetir e sem apagar nada; o
     *     transformador so escreve sobre o arquivo ja criado por esta chamada, com
     *     {@code FailureBehavior.LEAVE}, e o descarte e desta classe;</li>
     * <li><b>falha na limpeza.</b> O SDK apaga por {@code runAndLogError}: se a exclusao falha,
     *     vira log e a repeticao segue sobre estado local desconhecido. Aqui ela interrompe o
     *     download com {@link LimpezaDoParcialFalhouException}, que carrega as duas falhas e nao e
     *     repetida. Nesse caso nao ha promessa de remocao: a limpeza do espaco da tentativa e a
     *     varredura de orfaos do {@link EspacoDeTrabalhoAdapter} continuam valendo.</li>
     * </ol>
     *
     * <p>A limpeza entre chamadas e ao esgotar as repeticoes e a mesma: toda chamada que falha
     * descarta o proprio parcial antes de a falha chegar a {@link #comRepeticao}. Sucesso deixa
     * o arquivo completo no destino.
     *
     * <p><b>O limite da posse.</b> Ela vale pelo caminho, e nao pelo arquivo: quem apagasse o
     * parcial e criasse outro no mesmo caminho entre o {@code createFile} e a abertura, ou antes do
     * descarte, teria o arquivo truncado ou apagado. So esta operacao escreve no diretorio
     * exclusivo da tentativa ({@link EspacoDeTrabalhoAdapter#prepararNovo}); a guarda cobre o
     * arquivo que ja estava ali e o que aparece no instante da criacao, nao troca deliberada.
     */
    public CompletionStage<Path> baixar(String bucket, String chave, Path destino) {
        var requisicao = GetObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRepeticao(Uni.createFrom().deferred(() -> umDownload(requisicao, destino)), ArquivoMinioClient::eBlipDoDownload)
                .subscribeAsCompletionStage();
    }

    /**
     * Colisao no destino e limpeza que falhou nao sao blip: repetir da o mesmo resultado, e no
     * segundo caso sobre estado local desconhecido.
     */
    private static boolean eBlipDoDownload(Throwable falha) {
        return !(falha instanceof FileAlreadyExistsException || falha instanceof LimpezaDoParcialFalhouException);
    }

    private Uni<Path> umDownload(GetObjectRequest requisicao, Path destino) {
        try {
            Files.createFile(destino);
        } catch (IOException colisaoOuFalhaAoCriar) {
            return Uni.createFrom().failure(colisaoOuFalhaAoCriar);
        }
        return Uni.createFrom()
                .completionStage(() -> s3.getObject(requisicao, AsyncResponseTransformer.toFile(destino, SOBRE_O_PROPRIO_ARQUIVO)))
                .map(resposta -> destino)
                .onFailure().recoverWithUni(falha -> descartarParcial(destino, falha));
    }

    private static Uni<Path> descartarParcial(Path parcial, Throwable falhaDaTransferencia) {
        try {
            Files.deleteIfExists(parcial);
            return Uni.createFrom().failure(falhaDaTransferencia);
        } catch (IOException falhaDaLimpeza) {
            return Uni.createFrom().failure(
                    new LimpezaDoParcialFalhouException(parcial, falhaDaTransferencia, falhaDaLimpeza));
        }
    }

    public CompletionStage<Void> gravar(String bucket, String chave, Path origem) {
        var requisicao = PutObjectRequest.builder().bucket(bucket).key(chave).build();
        return comRepeticao(Uni.createFrom()
                .completionStage(() -> s3.putObject(requisicao, AsyncRequestBody.fromFile(origem)))
                .replaceWithVoid())
                .subscribeAsCompletionStage();
    }

    /**
     * A repeticao do ADR 0001: <b>3 chamadas ao recurso — a primeira mais 2 repeticoes</b>,
     * 2 s de espera, jitter de 10% e so sobre {@code Exception} — {@code Error} nao e repetido.
     * A aritmetica, o motivo do numero e a lista dos quatro lugares que a implementam moram no
     * ADR 0001, na emenda do ticket 086; aqui fica so o que o codigo precisa dizer:
     * {@code atMost(n)} do Mutiny conta as repeticoes <i>depois</i> da primeira chamada, e
     * {@code withBackOff(x, x)} e como ele escreve espera constante — backoff crescente seria
     * outra politica, que o ADR nao pediu.
     *
     * <p>A contagem e fixa e a espera e {@link #esperaEntreRepeticoes}: o numero de chamadas e a
     * politica, e a espera e o preco dela.
     *
     * <p><b>Repeticao, e nao "tentativa".</b> No {@code CONTEXT.md} tentativa e uma <i>entrega</i>
     * da mensagem ao worker; estas aqui sao chamadas de I/O dentro de <b>uma</b> tentativa.
     *
     * <p>O que <b>nao</b> veio junto do {@code @Retry} removido no ticket 061: o
     * {@code maxDuration} de 3 min. Ele nunca chegou a limitar nada, e o caso que parecia cobrir
     * — a chamada que nao volta — era o travamento daquele ticket.
     */
    private <T> Uni<T> comRepeticao(Uni<T> chamada) {
        return comRepeticao(chamada, falha -> true);
    }

    /**
     * A mesma repeticao, com um filtro a mais que e so do download (ticket 102), e nao parte comum
     * das copias: ver {@link #eBlipDoDownload}.
     */
    private <T> Uni<T> comRepeticao(Uni<T> chamada, Predicate<Throwable> repetivel) {
        return chamada.onFailure(falha -> falha instanceof Exception && repetivel.test(falha)).retry()
                .withBackOff(esperaEntreRepeticoes, esperaEntreRepeticoes)
                .withJitter(JITTER)
                .atMost(MAXIMO_DE_REPETICOES);
    }

    /**
     * O download parou porque o parcial de uma chamada que falhou nao pode ser descartado. A causa
     * e a falha da transferencia; a da limpeza vem em {@link #falhaDaLimpeza} e tambem como
     * suprimida, para aparecer no stack trace de quem so loga.
     */
    public static final class LimpezaDoParcialFalhouException extends IOException {

        private final Throwable falhaDaLimpeza;

        LimpezaDoParcialFalhouException(Path parcial, Throwable falhaDaTransferencia, Throwable falhaDaLimpeza) {
            super("download interrompido: nao consegui descartar o parcial " + parcial, falhaDaTransferencia);
            this.falhaDaLimpeza = falhaDaLimpeza;
            addSuppressed(falhaDaLimpeza);
        }

        public Throwable falhaDaTransferencia() {
            return getCause();
        }

        public Throwable falhaDaLimpeza() {
            return falhaDaLimpeza;
        }
    }
}
