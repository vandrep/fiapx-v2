package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;
import br.com.fiapx.videos.core.interfaces.sender.ExtracaoSender;
import br.com.fiapx.videos.core.interfaces.sender.NotificacaoSender;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Dublês dos gateways para os testes de use case. O core nao conhece banco nem S3, entao
 * estes testes rodam sem Docker e sem Quarkus — e essa e a prova de que a separacao vale.
 */
final class GatewaysEmMemoria {

    private GatewaysEmMemoria() {
    }

    static final class Videos implements VideoGateway {

        final Map<UUID, Video> armazenados = new LinkedHashMap<>();
        final Map<UUID, Instant> comandoPublicadoEm = new LinkedHashMap<>();
        final Map<UUID, Instant> falhaPublicadaEm = new LinkedHashMap<>();
        private final Map<UUID, EstadoVideo> corridasArmadas = new LinkedHashMap<>();

        /**
         * Arma a corrida perdida deste Video: assim que a leitura dele acontecer, outra
         * entrega move a linha para {@code destino} — uma vez so, e so para este id. O fluxo
         * segue com o Video que leu, tenta o UPDATE condicional e a guarda o reprova sozinha,
         * pela mesma regra de predecessores do WHERE real.
         */
        void outraEntregaVenceACorridaPara(UUID id, EstadoVideo destino) {
            corridasArmadas.put(id, destino);
        }

        @Override
        public CompletableFuture<Void> adicionar(Video video) {
            armazenados.put(video.id(), video);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Optional<Video>> buscarPorIdEDono(UUID id, Dono dono) {
            return CompletableFuture.completedFuture(Optional.ofNullable(armazenados.get(id))
                    .filter(video -> video.dono().sub().equals(dono.sub())));
        }

        /**
         * Devolve uma <b>copia</b> da linha, como o SELECT devolve uma leitura: transicionar o
         * Video lido nao move a linha, e por isso a guarda do UPDATE ainda tem o que julgar.
         *
         * <p>E tambem o ponto onde dispara a entrega concorrente armada por
         * {@link #outraEntregaVenceACorridaPara} — ler e o instante depois do qual a corrida
         * pode ser perdida.
         */
        @Override
        public CompletableFuture<Optional<Video>> buscarPorId(UUID id) {
            var lido = Optional.ofNullable(armazenados.get(id)).map(Videos::copia);
            var destinoDaCorrida = corridasArmadas.remove(id);
            if (destinoDaCorrida != null) {
                armazenados.computeIfPresent(id, (chave, linha) -> copiaEm(linha, destinoDaCorrida));
            }
            return CompletableFuture.completedFuture(lido);
        }

        @Override
        public CompletableFuture<Pagina<Video>> listarPorDono(Dono dono,
                                                              Optional<EstadoVideo> estado,
                                                              int pagina,
                                                              int tamanho) {
            var doDono = armazenados.values().stream()
                    .filter(video -> video.dono().sub().equals(dono.sub()))
                    .filter(video -> estado.map(filtro -> filtro == video.estado()).orElse(true))
                    .sorted(Comparator.comparing(Video::recebidoEm).reversed())
                    .toList();
            var fatia = new ArrayList<>(doDono).subList(
                    Math.min(pagina * tamanho, doDono.size()),
                    Math.min((pagina + 1) * tamanho, doDono.size()));
            return CompletableFuture.completedFuture(
                    new Pagina<>(List.copyOf(fatia), pagina, tamanho, doDono.size()));
        }

        @Override
        public CompletableFuture<Boolean> marcarIniciada(UUID id) {
            return transicionar(id, Video::marcaComoIniciada);
        }

        @Override
        public CompletableFuture<Boolean> marcarConcluida(UUID id,
                                                          Instant concluidaEm,
                                                          String chavePacote,
                                                          int quantidadeFrames,
                                                          long tamanhoPacoteBytes) {
            return transicionar(id, linha -> linha.marcaComoConcluida(
                    concluidaEm, chavePacote, quantidadeFrames, tamanhoPacoteBytes));
        }

        @Override
        public CompletableFuture<Boolean> marcarFalha(UUID id, Instant falhouEm, MotivoFalha motivo) {
            return transicionar(id, linha -> linha.marcaComoFalha(falhouEm, motivo));
        }

        /**
         * O UPDATE condicional do adapter, em memoria: a guarda do WHERE aqui e a propria
         * entidade, aplicada a <b>linha armazenada</b> — nunca ao Video que o use case ja
         * transicionou. Quem chegou depois de outra entrega ver a linha em um estado que nao
         * e predecessor do destino sai com {@code false}, como o UPDATE que altera zero linhas.
         */
        private CompletableFuture<Boolean> transicionar(UUID id, Transicao transicao) {
            var linha = armazenados.get(id);
            return CompletableFuture.completedFuture(linha != null && transicao.aplicarA(linha));
        }

        @Override
        public CompletableFuture<Void> marcarComandoPublicado(UUID id, Instant publicadoEm) {
            comandoPublicadoEm.put(id, publicadoEm);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> marcarFalhaPublicada(UUID id, Instant publicadoEm) {
            falhaPublicadaEm.put(id, publicadoEm);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<Video>> buscarComandosPendentes(Instant recebidosAntesDe, int tamanhoDoLote) {
            var pendentes = armazenados.values().stream()
                    .filter(video -> video.estado() == EstadoVideo.RECEBIDO)
                    .filter(video -> !comandoPublicadoEm.containsKey(video.id()))
                    .filter(video -> video.recebidoEm().isBefore(recebidosAntesDe))
                    .sorted(Comparator.comparing(Video::recebidoEm))
                    .limit(tamanhoDoLote)
                    .toList();
            return CompletableFuture.completedFuture(pendentes);
        }

        @Override
        public CompletableFuture<List<Video>> buscarFalhasPendentes(int tamanhoDoLote) {
            var pendentes = armazenados.values().stream()
                    .filter(video -> video.estado() == EstadoVideo.FALHOU)
                    .filter(video -> !falhaPublicadaEm.containsKey(video.id()))
                    .sorted(Comparator.comparing(Video::recebidoEm))
                    .limit(tamanhoDoLote)
                    .toList();
            return CompletableFuture.completedFuture(pendentes);
        }

        /** A transicao do dominio aplicada a linha: muda a linha, e diz se mudou. */
        @FunctionalInterface
        private interface Transicao {
            boolean aplicarA(Video linha);
        }

        /** A leitura do banco reconstitui um objeto novo a cada SELECT; aqui tambem. */
        private static Video copia(Video video) {
            return copiaEm(video, video.estado());
        }

        /** A mesma linha, no estado em que outra entrega a deixou. */
        private static Video copiaEm(Video video, EstadoVideo estado) {
            return Video.reconstituir(video.id(), video.nome(), video.tamanhoBytes(), video.dono(),
                    video.chaveVideo(), estado, video.recebidoEm(), video.finalizadoEm(),
                    video.chavePacote(), video.quantidadeFrames(), video.tamanhoPacoteBytes(),
                    video.motivo());
        }
    }

    static final class ExtracaoEnvios implements ExtracaoSender {

        final List<UUID> idsEnviados = new ArrayList<>();
        String ultimaChaveVideo;
        String ultimaChaveDestinoPacote;

        @Override
        public CompletableFuture<Void> enviarExtrairVideo(UUID idVideo, String chaveVideo, String chaveDestinoPacote) {
            idsEnviados.add(idVideo);
            ultimaChaveVideo = chaveVideo;
            ultimaChaveDestinoPacote = chaveDestinoPacote;
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class NotificacaoEnvios implements NotificacaoSender {

        final List<UUID> idsEnviados = new ArrayList<>();
        Dono ultimoDono;
        String ultimoNomeArquivo;
        MotivoFalha ultimoMotivo;
        Instant ultimoOcorridoEm;

        @Override
        public CompletableFuture<Void> enviarVideoFalhou(UUID idVideo,
                                                          Dono dono,
                                                          String nomeArquivoOriginal,
                                                          MotivoFalha motivo,
                                                          Instant ocorridoEm) {
            idsEnviados.add(idVideo);
            ultimoDono = dono;
            ultimoNomeArquivo = nomeArquivoOriginal;
            ultimoMotivo = motivo;
            ultimoOcorridoEm = ocorridoEm;
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class Arquivos implements ArquivoGateway {

        final Map<String, Flow.Publisher<ByteBuffer>> pacotes = new LinkedHashMap<>();
        Path ultimoArquivoGravado;

        @Override
        public CompletableFuture<String> gravarVideo(UUID idVideo, String nome, Path arquivo) {
            ultimoArquivoGravado = arquivo;
            return CompletableFuture.completedFuture(idVideo + "/original.mp4");
        }

        @Override
        public String chaveDoPacote(UUID idVideo) {
            return idVideo + ".zip";
        }

        @Override
        public CompletableFuture<Optional<Flow.Publisher<ByteBuffer>>> abrirPacote(String chavePacote) {
            return CompletableFuture.completedFuture(Optional.ofNullable(pacotes.get(chavePacote)));
        }
    }

    static final class Presenter implements VideoPresenter {

        VideoDTO recebido;

        @Override
        public void present(VideoDTO videoDTO) {
            this.recebido = videoDTO;
        }
    }
}
