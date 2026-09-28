package br.com.fiapx.videos.framework.db;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.ResultadoExtracao;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;
import br.com.fiapx.videos.core.usecases.video.EnviarVideoUseCase;
import br.com.fiapx.videos.core.usecases.video.ProcessarExtracaoFalhouUseCase;
import br.com.fiapx.videos.core.usecases.video.PublicarExtrairVideo;
import br.com.fiapx.videos.core.usecases.video.PublicarVideoFalhou;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O UPDATE condicional contra Postgres de verdade, e nao contra o duble em memoria.
 *
 * <p>Existe por causa do ticket 027: o predicado deixou de ser {@code estado = ?} e virou
 * {@code estado in ?}, alimentado por um {@code Set} vindo de
 * {@link EstadoVideo#predecessores()}. Isso e HQL parametrizado com colecao de enum — a
 * camada que nenhum teste do {@code core} alcanca, e o BDD tampouco, porque ele monta
 * CONCLUIDO atribuindo a entidade direto. Sem isto, a correcao do defeito 1 so seria
 * exercitada em producao.
 *
 * <p>{@code @RunOnVertxContext} nao e decoracao: {@code Panache.withTransaction} exige um
 * contexto Vert.x e do thread do JUnit levanta "No current Vertx context found".
 */
@QuarkusTest
class VideoDataSourceAdapterTest {

    private static final Dono DONO = new Dono("sub-adapter", "adapter@exemplo.com");
    /**
     * Folgado de proposito: estes cenarios julgam a ordem entre commit e publish, e precisam que
     * o envio espere o consumidor simulado terminar — o teto do ticket 104 nao esta em jogo.
     */
    private static final Duration TETO_DO_PUBLISH = Duration.ofSeconds(30);
    private static final Instant INICIADA_EM = Instant.parse("2026-09-14T10:00:00Z");

    @Test
    @RunOnVertxContext
    void iniciadaPersisteOsMesmosCamposQueAEntidade(UniAsserter asserter) {
        var id = new UUID[1];
        var esperado = new Video[1];
        gravarRecebido(asserter, id);
        carregarEsperado(asserter, id, esperado);

        asserter.execute(() -> {
            esperado[0].marcaComoIniciada(INICIADA_EM);
            return iniciar(id[0]);
        });
        asserter.assertThat(() -> videoDe(id[0]), atual -> assertVideoIgual(esperado[0], atual));
    }

    @Test
    @RunOnVertxContext
    void concluidaPersisteOsMesmosCamposQueAEntidade(UniAsserter asserter) {
        var id = new UUID[1];
        var esperado = new Video[1];
        var concluidaEm = Instant.parse("2026-09-04T12:00:00Z");
        gravarRecebido(asserter, id);
        carregarEsperado(asserter, id, esperado);

        asserter.execute(() -> {
            var resultado = new ResultadoExtracao(concluidaEm, "pacotes/resultado.zip", 900, 2_048L);
            esperado[0].marcaComoConcluida(resultado);
            return Uni.createFrom().completionStage(() -> adapter.marcarConcluida(id[0], resultado));
        });
        asserter.assertThat(() -> videoDe(id[0]), atual -> assertVideoIgual(esperado[0], atual));
    }

    @Test
    @RunOnVertxContext
    void falhaPersisteOsMesmosCamposQueAEntidade(UniAsserter asserter) {
        var id = new UUID[1];
        var esperado = new Video[1];
        var falhouEm = Instant.parse("2026-09-04T12:00:00Z");
        gravarRecebido(asserter, id);
        carregarEsperado(asserter, id, esperado);

        asserter.execute(() -> {
            esperado[0].marcaComoFalha(falhouEm, MotivoFalha.ARQUIVO_INVALIDO);
            return Uni.createFrom().completionStage(
                    () -> adapter.marcarFalha(id[0], falhouEm, MotivoFalha.ARQUIVO_INVALIDO));
        });
        asserter.assertThat(() -> videoDe(id[0]), atual -> assertVideoIgual(esperado[0], atual));
    }

    /**
     * As duas contagens do ticket 106 em HQL contra o Postgres. Os instantes sao de 1999 para
     * que nenhuma linha de outro cenario da mesma base caia antes do corte: a contagem e global,
     * nao por Video.
     */
    @Test
    @RunOnVertxContext
    void contagemDePresosJulgaAColunaCertaDeCadaEstado(UniAsserter asserter) {
        var antigo = Instant.parse("1999-01-01T00:00:00Z");
        var corte = antigo.plus(Duration.ofDays(1));
        var processando = new UUID[1];
        var recebido = new UUID[1];
        var concluido = new UUID[1];
        gravarRecebido(asserter, processando);
        gravarRecebido(asserter, recebido);
        gravarRecebido(asserter, concluido);

        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.marcarIniciada(processando[0], antigo)));
        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.marcarComandoPublicado(recebido[0], antigo)));
        // Comando antigo e terminal: nao conta, e o PROCESSANDO com marca antiga tambem nao
        // entra na contagem de RECEBIDO.
        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.marcarComandoPublicado(concluido[0], antigo)));
        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.marcarComandoPublicado(processando[0], antigo)));
        asserter.execute(() -> concluir(concluido[0]));

        asserter.assertThat(() -> Uni.createFrom().completionStage(() -> adapter.contarProcessandoIniciadosAntesDe(corte)),
                total -> assertEquals(1L, total));
        asserter.assertThat(() -> Uni.createFrom().completionStage(() -> adapter.contarRecebidosComComandoPublicadoAntesDe(corte)),
                total -> assertEquals(1L, total));
        // Estrito: no proprio instante ainda nao passou do limiar.
        asserter.assertThat(() -> Uni.createFrom().completionStage(() -> adapter.contarProcessandoIniciadosAntesDe(antigo)),
                total -> assertEquals(0L, total));
        asserter.assertThat(() -> Uni.createFrom().completionStage(() -> adapter.contarRecebidosComComandoPublicadoAntesDe(antigo)),
                total -> assertEquals(0L, total));
    }

    private void carregarEsperado(UniAsserter asserter, UUID[] id, Video[] esperado) {
        asserter.execute(() -> videoDe(id[0]).invoke(video -> esperado[0] = video));
    }

    private static void assertVideoIgual(Video esperado, Video atual) {
        assertEquals(esperado.id(), atual.id());
        assertEquals(esperado.nome(), atual.nome());
        assertEquals(esperado.tamanhoBytes(), atual.tamanhoBytes());
        assertEquals(esperado.dono(), atual.dono());
        assertEquals(esperado.chaveVideo(), atual.chaveVideo());
        assertEquals(esperado.estado(), atual.estado());
        assertEquals(esperado.recebidoEm(), atual.recebidoEm());
        assertEquals(esperado.iniciadaEm(), atual.iniciadaEm());
        assertEquals(esperado.finalizadoEm(), atual.finalizadoEm());
        assertEquals(esperado.chavePacote(), atual.chavePacote());
        assertEquals(esperado.quantidadeFrames(), atual.quantidadeFrames());
        assertEquals(esperado.tamanhoPacoteBytes(), atual.tamanhoPacoteBytes());
        assertEquals(esperado.motivo(), atual.motivo());
    }

    @Inject
    VideoDataSourceAdapter adapter;

    @Inject
    Pool pool;

    @Test
    @RunOnVertxContext
    void adicionarMesmoVideoNovamenteNaoCriaOutraLinha(UniAsserter asserter) {
        var video = Video.novo("idempotente.mp4", 1_024L, DONO).armazenadoEm("chave/idempotente.mp4");

        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.adicionar(video)));
        asserter.execute(() -> Uni.createFrom().completionStage(() -> adapter.adicionar(video)));
        asserter.assertThat(() -> linhasDoVideo(video.id()), linhas -> assertEquals(1L, linhas));
    }

    @Test
    @RunOnVertxContext
    void concluirDiretoDeRecebidoMudaALinha(UniAsserter asserter) {
        var id = new UUID[1];
        gravarRecebido(asserter, id);

        asserter.assertThat(() -> concluir(id[0]),
                mudou -> assertTrue(mudou, "a Concluida que chega antes da Iniciada tem de mudar a linha"));
        asserter.assertThat(() -> estadoDe(id[0]),
                estado -> assertEquals(EstadoVideo.CONCLUIDO, estado));
    }

    @Test
    @RunOnVertxContext
    void concluirSaindoDeProcessandoTambemMudaALinha(UniAsserter asserter) {
        var id = new UUID[1];
        gravarRecebido(asserter, id);

        asserter.assertThat(() -> iniciar(id[0]), mudou -> assertTrue(mudou));
        asserter.assertThat(() -> concluir(id[0]), mudou -> assertTrue(mudou));
        asserter.assertThat(() -> estadoDe(id[0]),
                estado -> assertEquals(EstadoVideo.CONCLUIDO, estado));
    }

    @Test
    @RunOnVertxContext
    void aSegundaConclusaoNaoMudaLinhaNenhuma(UniAsserter asserter) {
        var id = new UUID[1];
        gravarRecebido(asserter, id);

        asserter.assertThat(() -> concluir(id[0]), mudou -> assertTrue(mudou));
        asserter.assertThat(() -> concluir(id[0]), mudou -> assertFalse(mudou));
    }

    @Test
    @RunOnVertxContext
    void aIniciadaAtrasadaNaoDesfazOConcluido(UniAsserter asserter) {
        var id = new UUID[1];
        gravarRecebido(asserter, id);

        asserter.assertThat(() -> concluir(id[0]), mudou -> assertTrue(mudou));
        asserter.assertThat(() -> iniciar(id[0]), mudou -> assertFalse(mudou));
        asserter.assertThat(() -> estadoDe(id[0]),
                estado -> assertEquals(EstadoVideo.CONCLUIDO, estado));
    }

    /** A guarda de unicidade do e-mail continua valendo saindo de RECEBIDO (ADR 0001). */
    @Test
    @RunOnVertxContext
    void falharDiretoDeRecebidoDevolveOVideoUmaVezSo(UniAsserter asserter) {
        var id = new UUID[1];
        gravarRecebido(asserter, id);

        asserter.assertThat(() -> falhar(id[0]), primeira -> assertTrue(primeira));
        asserter.assertThat(() -> falhar(id[0]), segunda -> assertFalse(segunda));
        asserter.assertThat(() -> estadoDe(id[0]),
                estado -> assertEquals(EstadoVideo.FALHOU, estado));
    }

    /**
     * O predicado de folga da metade da falha e HQL sobre {@code finalizadoEm} (ticket 050),
     * e nenhum teste do {@code core} o alcanca: la o filtro e um {@code Stream}. Aqui a
     * mesma linha e julgada por dois cortes, contra Postgres de verdade.
     *
     * <p>O instante e fixo, e nao {@code Instant.now()}, porque {@code timestamptz} guarda
     * microssegundos: um {@code now()} com nanos voltaria do banco truncado <b>para tras</b>
     * do proprio corte, e o teste passaria ou nao conforme o relogio.
     */
    @Test
    @RunOnVertxContext
    void aFalhaRecemGravadaFicaForaDaVarreduraEAJaVelhaEntra(UniAsserter asserter) {
        var id = new UUID[1];
        var falhouEm = Instant.parse("2026-09-05T16:00:00Z");
        gravarRecebido(asserter, id);
        asserter.execute(() -> Uni.createFrom().completionStage(
                () -> adapter.marcarFalha(id[0], falhouEm, MotivoFalha.ARQUIVO_INVALIDO)));

        asserter.assertThat(() -> falhasPendentesAntesDe(falhouEm),
                pendentes -> assertFalse(contem(pendentes, id[0]),
                        "falha dentro da folga pode estar so aguardando o publish em voo"));
        asserter.assertThat(() -> falhasPendentesAntesDe(falhouEm.plusSeconds(1)),
                pendentes -> assertTrue(contem(pendentes, id[0]),
                        "passada a folga, a falha perdida tem de voltar a ser alcancada"));
    }

    /**
     * O predicado do resgate (ticket 107): {@code PROCESSANDO} sem marca volta a ser pendente,
     * e so o resgate apaga a marca de um {@code PROCESSANDO}. Com marca ele fica de fora, e o
     * terminal sem marca tambem, porque terminal e terminal.
     */
    @Test
    @RunOnVertxContext
    void processandoSemMarcaEPendenteEComMarcaNao(UniAsserter asserter) {
        var semMarca = new UUID[1];
        var comMarca = new UUID[1];
        var concluidoSemMarca = new UUID[1];
        gravarRecebido(asserter, semMarca);
        gravarRecebido(asserter, comMarca);
        gravarRecebido(asserter, concluidoSemMarca);
        asserter.execute(() -> iniciar(semMarca[0]));
        asserter.execute(() -> iniciar(comMarca[0]));
        asserter.execute(() -> Uni.createFrom().completionStage(
                () -> adapter.marcarComandoPublicado(comMarca[0], Instant.now())));
        asserter.execute(() -> concluir(concluidoSemMarca[0]));

        var corte = Instant.now().plus(Duration.ofDays(1));
        asserter.assertThat(() -> comandosPendentesAntesDe(corte), pendentes -> {
            assertTrue(contem(pendentes, semMarca[0]), "PROCESSANDO sem marca tem de ser republicado");
            assertFalse(contem(pendentes, comMarca[0]), "PROCESSANDO com marca ja tem comando");
            assertFalse(contem(pendentes, concluidoSemMarca[0]), "terminal nao recebe comando");
        });
    }

    /** Lote largo de proposito: a tabela do teste acumula linhas de outros cenarios. */
    private Uni<List<Video>> comandosPendentesAntesDe(Instant recebidosAntesDe) {
        return Uni.createFrom().completionStage(
                () -> adapter.buscarComandosPendentes(recebidosAntesDe, 1_000));
    }

    /** Lote largo de proposito: a tabela do teste acumula linhas de outros cenarios. */
    private Uni<List<Video>> falhasPendentesAntesDe(Instant falhadosAntesDe) {
        return Uni.createFrom().completionStage(
                () -> adapter.buscarFalhasPendentes(falhadosAntesDe, 1_000));
    }

    private static boolean contem(List<Video> pendentes, UUID id) {
        return pendentes.stream().anyMatch(video -> id.equals(video.id()));
    }

    /**
     * A confirmacao simulada de {@code ExtrairVideo} so completa depois de uma nova leitura
     * encontrar o Video e de a falha permanente rapida ser processada. Assim, o encadeamento
     * controlado pelo {@link UniAsserter} reproduz o consumidor que confirma o evento antes
     * de o caminho de envio encerrar, sem sleeps nem sorte (ticket 040).
     */
    @Test
    @RunOnVertxContext
    void confirmacaoDeEventoRapidoEnxergaOVideoEProduzFalha(UniAsserter asserter) {
        var arquivo = new ArquivoGatewayDeTeste();
        NotificacaoSender notificacao = (id, dono, nome, motivo, ocorridoEm) -> CompletableFuture.completedFuture(null);
        var processarFalha = new ProcessarExtracaoFalhouUseCase(adapter, arquivo,
                new PublicarVideoFalhou(notificacao, adapter));
        ExtracaoSender extracao = (id, chaveVideo, chaveDestinoPacote) -> adapter.buscarPorId(id)
                .thenCompose(encontrado -> {
                    assertTrue(encontrado.isPresent(),
                            "o consumidor do comando deve enxergar o Video ao confirma-lo");
                    return processarFalha.executar(new ProcessarExtracaoFalhouUseCase.Command(
                            id, MotivoFalha.ARQUIVO_INVALIDO, Instant.parse("2026-09-05T16:00:00Z")));
                });
        VideoPresenter presenter = video -> { };
        var envio = new EnviarVideoUseCase(arquivo, adapter,
                new PublicarExtrairVideo(arquivo, extracao, adapter), presenter, TETO_DO_PUBLISH);
        var id = new UUID[1];

        asserter.execute(() -> Uni.createFrom().completionStage(() -> envio.executar(
                new EnviarVideoUseCase.Command("visivel.mp4", "video/mp4", 1_024L,
                        Path.of("/tmp/visivel.mp4"), DONO)).thenAccept(video -> id[0] = video.id())));
        asserter.assertThat(() -> Uni.createFrom().completionStage(() -> adapter.buscarPorId(id[0])),
                encontrado -> assertEquals(EstadoVideo.FALHOU, encontrado.orElseThrow().estado()));
    }

    /**
     * A semantica de antes do ticket 040, reproduzida de proposito: com o envio inteiro dentro
     * de uma transacao — que era o efeito do {@code @WithTransaction} na borda —, o consumidor
     * que confirma o {@code ExtrairVideo} le por outra conexao e nao acha o Video. E a corrida
     * do ticket, e ela nao depende de sleep nem de sorte: a leitura acontece dentro da
     * transacao, que so commita depois.
     */
    @Test
    @RunOnVertxContext
    void envioDentroDeUmaTransacaoConfirmaOComandoSemOVideoEstarVisivel(UniAsserter asserter) {
        var linhasVistas = new Long[1];

        asserter.execute(() -> Panache.withTransaction(() -> Uni.createFrom().completionStage(
                () -> envioComConsumidorQueLePorOutraConexao(linhasVistas).executar(envioDe("na-transacao.mp4")))));

        asserter.assertThat(() -> Uni.createFrom().item(linhasVistas[0]), linhas -> assertEquals(0L, linhas,
                "antes do commit o consumidor confirmaria o comando sem achar o Video"));
    }

    /**
     * O mesmo caminho sem transacao ambiente, que e como o {@code VideosResource} chama hoje:
     * {@link VideoDataSourceAdapter#adicionar} commita, e so entao o comando e publicado.
     */
    @Test
    @RunOnVertxContext
    void envioSemTransacaoAmbienteConfirmaOComandoComOVideoJaVisivel(UniAsserter asserter) {
        var linhasVistas = new Long[1];

        asserter.execute(() -> Uni.createFrom().completionStage(
                () -> envioComConsumidorQueLePorOutraConexao(linhasVistas).executar(envioDe("commitado.mp4"))));

        asserter.assertThat(() -> Uni.createFrom().item(linhasVistas[0]), linhas -> assertEquals(1L, linhas,
                "o publish do ExtrairVideo acontece com a linha ja visivel de fora"));
    }

    /**
     * O {@code ExtracaoSender} faz as vezes do consumidor que confirma o comando: ele le o
     * Video por uma <b>conexao propria do pool</b>, que e o que outro processo enxergaria.
     *
     * <p>O que fica registrado e a contagem de linhas, e nao um boolean: {@code null} denuncia
     * um consumidor que nunca rodou, que de outro modo passaria por "nao enxergou".
     */
    private EnviarVideoUseCase envioComConsumidorQueLePorOutraConexao(Long[] linhasVistas) {
        var arquivo = new ArquivoGatewayDeTeste();
        ExtracaoSender consumidor = (id, chaveVideo, chaveDestinoPacote) ->
                linhasVisiveisPorOutraConexao(id)
                        .invoke(linhas -> linhasVistas[0] = linhas)
                        .replaceWithVoid()
                        .subscribeAsCompletionStage();
        VideoPresenter presenter = video -> { };
        return new EnviarVideoUseCase(arquivo, adapter,
                new PublicarExtrairVideo(arquivo, consumidor, adapter), presenter, TETO_DO_PUBLISH);
    }

    private static EnviarVideoUseCase.Command envioDe(String nome) {
        return new EnviarVideoUseCase.Command(nome, "video/mp4", 1_024L, Path.of("/tmp/" + nome), DONO);
    }

    /** Conexao propria do pool: e o que um consumidor em outro processo enxergaria. */
    private Uni<Long> linhasVisiveisPorOutraConexao(UUID id) {
        return pool.withConnection(conexao -> conexao
                .preparedQuery("select count(*) from video where id = $1")
                .execute(Tuple.of(id))
                .map(linhas -> linhas.iterator().next().getLong(0)));
    }

    private static final class ArquivoGatewayDeTeste implements ArquivoGateway {

        @Override
        public CompletableFuture<String> gravarVideo(UUID idVideo, String nome, Path arquivo) {
            return CompletableFuture.completedFuture(idVideo + "/original.mp4");
        }

        @Override
        public CompletableFuture<Void> marcarDesfechoDoOriginal(String chaveVideo) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apagarOriginal(String chaveVideo) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String chaveDoPacote(UUID idVideo) {
            return idVideo + ".zip";
        }

        @Override
        public CompletableFuture<Optional<Flow.Publisher<ByteBuffer>>> abrirPacote(String chavePacote) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private void gravarRecebido(UniAsserter asserter, UUID[] id) {
        asserter.execute(() -> {
            var video = Video.novo("adapter.mp4", 1_024L, DONO).armazenadoEm("chave/original.mp4");
            id[0] = video.id();
            return Uni.createFrom().completionStage(() -> adapter.adicionar(video));
        });
    }

    private Uni<Boolean> iniciar(UUID id) {
        return Uni.createFrom().completionStage(() -> adapter.marcarIniciada(id, INICIADA_EM));
    }

    private Uni<Boolean> concluir(UUID id) {
        return Uni.createFrom().completionStage(() -> adapter.marcarConcluida(
                id, new ResultadoExtracao(Instant.now(), id + ".zip", 900, 2_048L)));
    }

    private Uni<Boolean> falhar(UUID id) {
        return Uni.createFrom().completionStage(
                () -> adapter.marcarFalha(id, Instant.now(), MotivoFalha.ARQUIVO_INVALIDO));
    }

    private Uni<Video> videoDe(UUID id) {
        return Uni.createFrom().completionStage(() -> adapter.buscarPorId(id))
                .map(video -> video.orElseThrow());
    }

    private Uni<EstadoVideo> estadoDe(UUID id) {
        return Panache.withSession(
                () -> VideoEntity.<VideoEntity>findById(id).map(entidade -> entidade.estado));
    }

    private Uni<Long> linhasDoVideo(UUID id) {
        return pool.withConnection(conexao -> conexao
                .preparedQuery("select count(*) from video where id = $1")
                .execute(Tuple.of(id))
                .map(linhas -> linhas.iterator().next().getLong(0)));
    }
}
