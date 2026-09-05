package br.com.fiapx.videos.framework.db;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.framework.db.entities.VideoEntity;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    @Test
    @RunOnVertxContext
    void iniciadaPersisteOsMesmosCamposQueAEntidade(UniAsserter asserter) {
        var id = new UUID[1];
        var esperado = new Video[1];
        gravarRecebido(asserter, id);
        carregarEsperado(asserter, id, esperado);

        asserter.execute(() -> {
            esperado[0].marcaComoIniciada();
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
            esperado[0].marcaComoConcluida(concluidaEm, "pacotes/resultado.zip", 900, 2_048L);
            return Uni.createFrom().completionStage(() -> adapter.marcarConcluida(
                    id[0], concluidaEm, "pacotes/resultado.zip", 900, 2_048L));
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
        assertEquals(esperado.finalizadoEm(), atual.finalizadoEm());
        assertEquals(esperado.chavePacote(), atual.chavePacote());
        assertEquals(esperado.quantidadeFrames(), atual.quantidadeFrames());
        assertEquals(esperado.tamanhoPacoteBytes(), atual.tamanhoPacoteBytes());
        assertEquals(esperado.motivo(), atual.motivo());
    }

    @Inject
    VideoDataSourceAdapter adapter;

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
     * {@code adicionar} e o unico metodo do gateway sem {@code Panache.with*} proprio: no
     * caminho de producao ele corre dentro da transacao que o envio ja abriu. Aqui a
     * transacao entra por fora, entao.
     */
    private void gravarRecebido(UniAsserter asserter, UUID[] id) {
        asserter.execute(() -> {
            var video = Video.novo("adapter.mp4", 1_024L, DONO).armazenadoEm("chave/original.mp4");
            id[0] = video.id();
            return Panache.withTransaction(
                    () -> Uni.createFrom().completionStage(() -> adapter.adicionar(video)));
        });
    }

    private Uni<Boolean> iniciar(UUID id) {
        return Uni.createFrom().completionStage(() -> adapter.marcarIniciada(id));
    }

    private Uni<Boolean> concluir(UUID id) {
        return Uni.createFrom().completionStage(
                () -> adapter.marcarConcluida(id, Instant.now(), id + ".zip", 900, 2_048L));
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
}
