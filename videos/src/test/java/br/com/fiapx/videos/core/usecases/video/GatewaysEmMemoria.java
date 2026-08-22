package br.com.fiapx.videos.core.usecases.video;

import br.com.fiapx.videos.core.entities.Dono;
import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.Video;
import br.com.fiapx.videos.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.videos.core.interfaces.gateway.VideoGateway;
import br.com.fiapx.videos.core.interfaces.presenter.VideoPresenter;
import br.com.fiapx.videos.core.interfaces.presenter.dto.Pagina;
import br.com.fiapx.videos.core.interfaces.presenter.dto.VideoDTO;

import java.nio.ByteBuffer;
import java.nio.file.Path;
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
