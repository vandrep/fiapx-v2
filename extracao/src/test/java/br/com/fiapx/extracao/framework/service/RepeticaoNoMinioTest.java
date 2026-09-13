package br.com.fiapx.extracao.framework.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.utils.async.SimplePublisher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que o ADR 0001 promete no acesso ao MinIO: um blip de I/O e absorvido por
 * {@link ArquivoMinioClient}, e um armazenamento que nao volta acaba falhando em vez de
 * insistir para sempre.
 *
 * <p>Este teste substitui o {@code RetryComCompletionStageTest}, que travava o comportamento
 * do {@code @Retry} do SmallRye Fault Tolerance. O ticket 061 tirou aquele interceptor daqui —
 * ele reagendava a chamada no contexto Vert.x do consumidor e a prendia la para sempre —, e o
 * que precisa continuar travado e a <b>politica</b>, nao a anotacao que a implementava: o
 * numero de chamadas ao MinIO e o fato de a ultima falha chegar ao chamador.
 *
 * <p><b>Sao tres chamadas ao recurso — a primeira mais duas repeticoes</b>, que e a aritmetica
 * unica do ADR 0001 desde o ticket 086. Quem guarda esse numero e o cenario do recurso
 * persistentemente fora, que conta as chamadas ate a desistencia; o cenario do blip falha as
 * duas primeiras de proposito, entao sucede na ultima chamada que a politica permite e reprova
 * se a repeticao sumir — uma repeticao a mais ele nao pegaria.
 *
 * <p>Sem container de proposito: monta-lo a mao dispensa o boot do Quarkus e deixa a espera
 * entre repeticoes ser atribuida direto no campo.
 *
 * <p><b>A espera aqui e 1 ms, e nao os 2 s de producao</b> (ticket 085). O que esta sob
 * julgamento e a repeticao <i>acontecer</i> e a ultima falha chegar ao chamador, nao quanto ela
 * espera; com os 2 s a classe levava <b>14,34 s</b>, e com 1 ms leva <b>0,25 s</b>. Que os
 * 2 s do ADR 0001 sejam 2 s fica guardado pelo default do {@code @ConfigProperty}, e nao por
 * cenario. O piso e 1 ms e nao zero: o Mutiny recusa backoff zero com
 * {@code IllegalArgumentException} na subscricao.
 *
 * <p><b>Dois dublês, porque falham em pontos diferentes.</b> O {@link S3QueFalhaAsPrimeiras} falha
 * <i>antes</i> de {@code prepare()}, entao julga so a contagem. O {@link S3QueFalhaDepoisDeEscrever}
 * (ticket 102) passa pelo transformador real do SDK e derruba o stream depois de ver bytes no
 * disco, que e onde mora o arquivo local. Com ele, a separacao que a sondagem daquele ticket fez
 * fica registrada nos cenarios:
 *
 * <ul>
 * <li><b>ja passava antes do 102</b>, pela limpeza do proprio SDK: blip depois da escrita baixa de
 *     novo com conteudo exato, falha persistente esgota as tres chamadas sem sobra, e downloads em
 *     espacos diferentes nao se misturam. O comentario antigo — de que esse blip queimava as
 *     repeticoes com {@code FileAlreadyExistsException} — estava errado;</li>
 * <li><b>ficou vermelho ate o 102</b>, porque a exclusao do SDK nao olha posse nem relata falha:
 *     destino preexistente apagado, arquivo alheio criado no instante da abertura apagado, e
 *     limpeza que falha seguida de mais repeticoes sobre estado local desconhecido.</li>
 * </ul>
 */
class RepeticaoNoMinioTest {

    private static final byte[] VIDEO = "conteudo de video para teste".getBytes();

    /** O armazenamento que nao volta: falha em toda chamada, nao so nas primeiras. */
    private static final int SEMPRE = Integer.MAX_VALUE;

    /** O piso que o Mutiny aceita: backoff zero e recusado com {@code IllegalArgumentException}. */
    private static final Duration ESPERA_DO_TESTE = Duration.ofMillis(1);

    @Test
    void blipNoDownloadEAbsorvidoPelaRepeticao() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(2);
        var destino = Files.createTempDirectory("t061").resolve("original.mp4");

        var baixado = clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get();

        assertEquals(destino, baixado);
        assertTrue(Files.exists(destino), "o Video tinha de estar em disco depois do blip");
        assertEquals(3, s3.chamadas(), "duas falhas mais a que sucedeu");
    }

    @Test
    void armazenamentoPersistentementeForaFalhaEmVezDeInsistirParaSempre() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(SEMPRE);
        var destino = Files.createTempDirectory("t061").resolve("original.mp4");

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get());

        assertTrue(falha.getCause() instanceof SdkClientException,
                "a ultima falha do MinIO tinha de chegar ao chamador, e chegou " + falha.getCause());
        assertEquals(3, s3.chamadas(), "a primeira chamada mais as duas repeticoes do ADR 0001");
    }

    @Test
    void blipNoUploadEAbsorvidoPelaRepeticao() throws Exception {
        var s3 = new S3QueFalhaAsPrimeiras(2);
        var origem = Files.createTempFile("t061", ".zip");
        Files.write(origem, VIDEO);

        clienteCom(s3).gravar("pacotes", "chave.zip", origem).toCompletableFuture().get();

        assertEquals(3, s3.chamadas(), "duas falhas mais a que sucedeu");
    }

    @Test
    void blipDepoisDaEscritaObservadaBaixaDeNovoDoZero() throws Exception {
        var destino = Files.createTempDirectory("t102").resolve("original.mp4");
        var s3 = new S3QueFalhaDepoisDeEscrever(2, Map.of("chave/original.mp4", destino));

        clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get(10, SECONDS);

        assertEquals("inicio-chave/original.mp4-fim", Files.readString(destino),
                "sem prefixo duplicado nem sobra do parcial anterior");
        assertEquals(3, s3.chamadas(), "duas falhas depois da escrita mais a que sucedeu");
        assertEquals(2, s3.escritasObservadas(), "o blip tinha de chegar depois de bytes em disco");
    }

    @Test
    void falhaPersistenteDepoisDaEscritaEsgotaAsChamadasSemDeixarParcial() throws Exception {
        var espaco = Files.createTempDirectory("t102");
        var destino = espaco.resolve("original.mp4");
        var s3 = new S3QueFalhaDepoisDeEscrever(SEMPRE, Map.of("chave/original.mp4", destino));

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get(10, SECONDS));

        assertTrue(falha.getCause() instanceof IOException, "a falha da transferencia chega ao chamador: " + falha.getCause());
        assertEquals(3, s3.chamadas(), "a primeira chamada mais as duas repeticoes do ADR 0001");
        assertEquals(3, s3.escritasObservadas());
        try (var sobras = Files.list(espaco)) {
            assertEquals(List.of(), sobras.toList(), "nenhum parcial proprio pode sobrar");
        }
    }

    @Test
    void destinoPreexistenteFicaIntactoEAOperacaoFalha() throws Exception {
        var destino = Files.createTempDirectory("t102").resolve("original.mp4");
        Files.writeString(destino, "arquivo de outro dono");
        var s3 = new S3QueFalhaDepoisDeEscrever(0, Map.of("chave/original.mp4", destino));

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get(10, SECONDS));

        assertTrue(falha.getCause() instanceof FileAlreadyExistsException, "e colisao, e chegou " + falha.getCause());
        assertEquals("arquivo de outro dono", Files.readString(destino));
        assertEquals(0, s3.chamadas(), "colisao nao e blip: nao gasta chamada ao MinIO nem repeticao");
    }

    /**
     * A corrida que um teste de existencia antes da abertura nao fecha: outro dono cria o destino
     * no instante em que a transferencia vai comecar — na primeira chamada e depois de cada limpeza.
     * O dublê tenta esse {@code CREATE_NEW} antes de entregar os bytes; se conseguir, a operacao
     * ainda nao tinha tomado posse do caminho, e o transformador apagaria o arquivo alheio.
     */
    @Test
    void posseDoDestinoETomadaAntesDeCadaChamada() throws Exception {
        var destino = Files.createTempDirectory("t102").resolve("original.mp4");
        var alheioCriado = new AtomicBoolean();
        var s3 = new S3QueFalhaDepoisDeEscrever(2, Map.of("chave/original.mp4", destino));
        s3.antesDaTransferencia = caminho -> {
            try {
                Files.writeString(caminho, "arquivo de outro dono", StandardOpenOption.CREATE_NEW);
                alheioCriado.set(true);
            } catch (FileAlreadyExistsException esperado) {
                // o caminho ja e da operacao
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
        };

        clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get(10, SECONDS);

        assertFalse(alheioCriado.get(), "outro dono conseguiu criar o destino no meio da operacao");
        assertEquals("inicio-chave/original.mp4-fim", Files.readString(destino));
        assertEquals(3, s3.chamadas());
    }

    /**
     * A limpeza falha de forma deterministica, sem depender de permissao que {@code root} ignora:
     * depois dos bytes em disco, o dublê troca o parcial por um diretorio nao vazio no mesmo
     * caminho, e {@code Files.delete} recusa diretorio nao vazio para qualquer usuario.
     */
    @Test
    void falhaNaLimpezaInterrompeODownloadComAsDuasFalhas() throws Exception {
        var destino = Files.createTempDirectory("t102").resolve("original.mp4");
        var s3 = new S3QueFalhaDepoisDeEscrever(SEMPRE, Map.of("chave/original.mp4", destino));
        s3.depoisDaEscrita = caminho -> {
            try {
                Files.delete(caminho);
                Files.createFile(Files.createDirectory(caminho).resolve("impede-a-limpeza"));
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
        };

        var falha = assertThrows(ExecutionException.class,
                () -> clienteCom(s3).baixar("videos", "chave/original.mp4", destino).toCompletableFuture().get(10, SECONDS));

        var interrupcao = assertInstanceOf(ArquivoMinioClient.LimpezaDoParcialFalhouException.class, falha.getCause());
        assertEquals("conexao caiu no meio do corpo", interrupcao.falhaDaTransferencia().getMessage());
        assertInstanceOf(DirectoryNotEmptyException.class, interrupcao.falhaDaLimpeza());
        assertEquals(1, s3.chamadas(), "estado local invalido nao pode ser repetido");
    }

    @Test
    void downloadsEmEspacosDiferentesNaoInterferemEntreSi() throws Exception {
        var destinoA = Files.createTempDirectory("t102").resolve("original.mp4");
        var destinoB = Files.createTempDirectory("t102").resolve("original.mp4");
        var s3 = new S3QueFalhaDepoisDeEscrever(2, Map.of("a/original.mp4", destinoA, "b/original.mp4", destinoB));
        var cliente = clienteCom(s3);

        // Executor proprio, e nao o common pool: na suite inteira as threads dele herdam o
        // classloader de um @QuarkusTest anterior, e o Mutiny recusa a propria extensao de
        // contexto carregada por ele (ServiceConfigurationError "not a subtype").
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = CompletableFuture.supplyAsync(() -> cliente.baixar("videos", "a/original.mp4", destinoA), threads)
                    .thenCompose(f -> f);
            var b = CompletableFuture.supplyAsync(() -> cliente.baixar("videos", "b/original.mp4", destinoB), threads)
                    .thenCompose(f -> f);

            assertEquals(destinoA, a.get(10, SECONDS));
            assertEquals(destinoB, b.get(10, SECONDS));
        }
        assertEquals("inicio-a/original.mp4-fim", Files.readString(destinoA));
        assertEquals("inicio-b/original.mp4-fim", Files.readString(destinoB));
    }

    private static ArquivoMinioClient clienteCom(S3AsyncClient s3) {
        var cliente = new ArquivoMinioClient();
        cliente.s3 = s3;
        cliente.esperaEntreRepeticoes = ESPERA_DO_TESTE;
        return cliente;
    }

    /**
     * O armazenamento instavel, no mesmo desenho do dublê que o `videos` ja usa: as
     * {@code falhasIniciais} primeiras chamadas falham como o SDK falha quando nao alcanca o
     * endpoint, e as seguintes sucedem. Conta <b>chamadas</b>, e nao "tentativas": no
     * {@code CONTEXT.md} tentativa e uma entrega da mensagem ao worker, e nao uma ida ao MinIO.
     */
    private static class S3QueFalhaAsPrimeiras implements S3AsyncClient {

        private final int falhasIniciais;
        private final AtomicInteger chamadas = new AtomicInteger();

        private S3QueFalhaAsPrimeiras(int falhasIniciais) {
            this.falhasIniciais = falhasIniciais;
        }

        private int chamadas() {
            return chamadas.get();
        }

        @Override
        public CompletableFuture<PutObjectResponse> putObject(PutObjectRequest requisicao, AsyncRequestBody corpo) {
            if (falharDestaVez()) {
                return CompletableFuture.failedFuture(inalcancavel());
            }
            return CompletableFuture.completedFuture(PutObjectResponse.builder().build());
        }

        @Override
        public <T> CompletableFuture<T> getObject(
                GetObjectRequest requisicao, AsyncResponseTransformer<GetObjectResponse, T> transformador) {
            if (falharDestaVez()) {
                return CompletableFuture.failedFuture(inalcancavel());
            }
            var resultado = transformador.prepare();
            transformador.onResponse(GetObjectResponse.builder().contentLength((long) VIDEO.length).build());
            var bytes = new SimplePublisher<ByteBuffer>();
            transformador.onStream(SdkPublisher.adapt(bytes));
            bytes.send(ByteBuffer.wrap(VIDEO));
            bytes.complete();
            return resultado;
        }

        private boolean falharDestaVez() {
            return chamadas.incrementAndGet() <= falhasIniciais;
        }

        private static SdkClientException inalcancavel() {
            return SdkClientException.create("MinIO inalcancavel nesta chamada");
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }

    /**
     * O blip que o dublê anterior nao alcanca: a chamada chega ao transformador <b>real</b> do SDK,
     * que abre o destino e grava o comeco do corpo; o dublê espera esses bytes ficarem visiveis no
     * disco e so entao derruba o stream com {@link IOException}. As chamadas seguintes as
     * {@code falhasIniciais} entregam o corpo inteiro. O corpo leva a chave, para que dois downloads
     * simultaneos denunciem troca de conteudo entre destinos.
     */
    private static class S3QueFalhaDepoisDeEscrever implements S3AsyncClient {

        private static final String PREFIXO = "inicio-";

        private final int falhasIniciais;
        private final Map<String, Path> destinos;
        private final Map<String, AtomicInteger> chamadasPorChave = new ConcurrentHashMap<>();
        private final AtomicInteger chamadas = new AtomicInteger();
        private final AtomicInteger escritasObservadas = new AtomicInteger();
        private volatile Consumer<Path> antesDaTransferencia = caminho -> { };
        private volatile Consumer<Path> depoisDaEscrita = caminho -> { };

        private S3QueFalhaDepoisDeEscrever(int falhasIniciais, Map<String, Path> destinos) {
            this.falhasIniciais = falhasIniciais;
            this.destinos = destinos;
        }

        private int chamadas() {
            return chamadas.get();
        }

        private int escritasObservadas() {
            return escritasObservadas.get();
        }

        @Override
        public <T> CompletableFuture<T> getObject(
                GetObjectRequest requisicao, AsyncResponseTransformer<GetObjectResponse, T> transformador) {
            chamadas.incrementAndGet();
            var chave = requisicao.key();
            var destino = destinos.get(chave);
            var falhar = chamadasPorChave.computeIfAbsent(chave, k -> new AtomicInteger()).incrementAndGet() <= falhasIniciais;
            antesDaTransferencia.accept(destino);

            var corpo = (PREFIXO + chave + "-fim").getBytes(StandardCharsets.UTF_8);
            var resultado = transformador.prepare();
            transformador.onResponse(GetObjectResponse.builder().contentLength((long) corpo.length).build());
            var bytes = new SimplePublisher<ByteBuffer>();
            transformador.onStream(SdkPublisher.adapt(bytes));
            if (!falhar) {
                bytes.send(ByteBuffer.wrap(corpo));
                bytes.complete();
                return resultado;
            }
            bytes.send(ByteBuffer.wrap(PREFIXO.getBytes(StandardCharsets.UTF_8)));
            esperarBytesEmDisco(destino, PREFIXO.length());
            escritasObservadas.incrementAndGet();
            depoisDaEscrita.accept(destino);
            bytes.error(new IOException("conexao caiu no meio do corpo"));
            return resultado;
        }

        private static void esperarBytesEmDisco(Path destino, long quantos) {
            var limite = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            try {
                while (!(Files.isRegularFile(destino) && Files.size(destino) >= quantos)) {
                    if (System.nanoTime() > limite) {
                        throw new AssertionError("os bytes nunca apareceram em " + destino);
                    }
                    Thread.onSpinWait();
                }
            } catch (IOException erro) {
                throw new UncheckedIOException(erro);
            }
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
        }
    }
}
