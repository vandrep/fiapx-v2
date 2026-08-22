package br.com.fiapx.extracao.core.usecases.extracao;

import br.com.fiapx.extracao.core.entities.MotivoFalha;
import br.com.fiapx.extracao.core.entities.ResultadoExtracao;
import br.com.fiapx.extracao.core.exceptions.FalhaPermanenteDeExtracaoException;
import br.com.fiapx.extracao.core.exceptions.FalhaTransitoriaDeExtracaoException;
import br.com.fiapx.extracao.core.interfaces.gateway.ArquivoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.EspacoDeTrabalhoGateway;
import br.com.fiapx.extracao.core.interfaces.gateway.ExtracaoDeFramesGateway;
import br.com.fiapx.extracao.core.interfaces.sender.ExtracaoEventosSender;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Dubles em memoria para o `core` inteiro, no mesmo espirito do {@code GatewaysEmMemoria} do
 * `videos` (ticket 017): nenhum I/O real, so os eventos publicados e as chamadas registradas.
 */
final class GatewaysEmMemoria {

    private GatewaysEmMemoria() {
    }

    static final class ArquivoGatewayEmMemoria implements ArquivoGateway {
        final List<String> chavesBaixadas = new ArrayList<>();
        final List<String> chavesGravadas = new ArrayList<>();
        Path caminhoDoVideoBaixado = Path.of("video-falso.mp4");
        RuntimeException falhaAoBaixar;
        RuntimeException falhaAoGravar;

        @Override
        public CompletableFuture<Path> baixarVideo(Path diretorio, String chaveVideo) {
            chavesBaixadas.add(chaveVideo);
            if (falhaAoBaixar != null) {
                return CompletableFuture.failedFuture(falhaAoBaixar);
            }
            return CompletableFuture.completedFuture(diretorio.resolve(caminhoDoVideoBaixado));
        }

        @Override
        public CompletableFuture<Void> gravarPacote(String chaveDestinoPacote, Path pacoteLocal) {
            chavesGravadas.add(chaveDestinoPacote);
            if (falhaAoGravar != null) {
                return CompletableFuture.failedFuture(falhaAoGravar);
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    static final class ExtracaoDeFramesGatewayEmMemoria implements ExtracaoDeFramesGateway {
        ResultadoExtracao resultado = new ResultadoExtracao(42, 1024L);
        Supplier<RuntimeException> falha;
        Duration tetoRecebido;

        @Override
        public CompletableFuture<ResultadoExtracao> processar(Path video, Path diretorioDeTrabalho,
                                                               Path destinoZip, Duration tetoDuracao) {
            this.tetoRecebido = tetoDuracao;
            if (falha != null) {
                return CompletableFuture.failedFuture(falha.get());
            }
            return CompletableFuture.completedFuture(resultado);
        }
    }

    static final class EspacoDeTrabalhoGatewayEmMemoria implements EspacoDeTrabalhoGateway {
        final List<UUID> preparados = new ArrayList<>();
        final List<UUID> limpos = new ArrayList<>();

        @Override
        public CompletableFuture<Path> prepararNovo(UUID idVideo) {
            preparados.add(idVideo);
            return CompletableFuture.completedFuture(Path.of("/scratch/" + idVideo));
        }

        @Override
        public CompletableFuture<Void> limpar(UUID idVideo) {
            limpos.add(idVideo);
            return CompletableFuture.completedFuture(null);
        }
    }

    record Falha(UUID idVideo, MotivoFalha motivo, String detalheTecnico, Instant ocorridoEm) {
    }

    record Concluida(UUID idVideo, String chavePacote, int quantidadeFrames, long tamanhoBytes, Instant concluidaEm) {
    }

    static final class ExtracaoEventosSenderEmMemoria implements ExtracaoEventosSender {
        final List<UUID> iniciadas = new ArrayList<>();
        final List<Concluida> concluidas = new ArrayList<>();
        final List<Falha> falhas = new ArrayList<>();

        @Override
        public CompletableFuture<Void> enviarIniciada(UUID idVideo, Instant iniciadaEm) {
            iniciadas.add(idVideo);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> enviarConcluida(UUID idVideo, String chavePacote, int quantidadeFrames,
                                                        long tamanhoBytes, Instant concluidaEm) {
            concluidas.add(new Concluida(idVideo, chavePacote, quantidadeFrames, tamanhoBytes, concluidaEm));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> enviarFalhou(UUID idVideo, MotivoFalha motivo, String detalheTecnico,
                                                     Instant ocorridoEm) {
            falhas.add(new Falha(idVideo, motivo, detalheTecnico, ocorridoEm));
            return CompletableFuture.completedFuture(null);
        }
    }

    static RuntimeException falhaPermanente(MotivoFalha motivo) {
        return new FalhaPermanenteDeExtracaoException(motivo, "detalhe de teste");
    }

    static RuntimeException falhaTransitoria() {
        return new FalhaTransitoriaDeExtracaoException("falha de teste");
    }
}
