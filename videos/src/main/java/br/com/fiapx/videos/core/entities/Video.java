package br.com.fiapx.videos.core.entities;

import java.time.Instant;
import java.util.UUID;

/**
 * O arquivo que o usuario enviou, junto com tudo que o sistema sabe sobre ele.
 *
 * <p>A Extracao nao e entidade: o `videos` nao ve tentativas, so entregas. O resultado da
 * Extracao e atributo do Video, e o Pacote e sub-recurso justamente por nao ter identidade
 * propria (ticket 009).
 *
 * <p>A impureza fica contida na criacao: so {@link #novo} olha o relogio. As transicoes
 * <b>recebem o instante de fora</b> — o do evento, nao o de agora —, o que as torna
 * deterministicas no teste.
 */
public final class Video {

    private final UUID id;
    private final String nome;
    private final long tamanhoBytes;
    private final Dono dono;
    private final Instant recebidoEm;

    private String chaveVideo;
    private EstadoVideo estado;
    private Instant finalizadoEm;
    private String chavePacote;
    private Integer quantidadeFrames;
    private Long tamanhoPacoteBytes;
    private MotivoFalha motivo;

    private Video(UUID id, String nome, long tamanhoBytes, Dono dono, Instant recebidoEm) {
        this.id = id;
        this.nome = nome;
        this.tamanhoBytes = tamanhoBytes;
        this.dono = dono;
        this.recebidoEm = recebidoEm;
    }

    /**
     * Video recem-enviado, ainda sem chave: quem constroi a chave e o {@code ArquivoGateway},
     * que precisa deste id para monta-la. O envio e um vai-e-volta —
     * {@code novo} -> {@code gravarVideo(video.id(), ...)} -> {@link #armazenadoEm} — e so
     * depois dele o Video pode ser persistido.
     */
    public static Video novo(String nome, long tamanhoBytes, Dono dono) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Nome do arquivo é obrigatório");
        }
        if (tamanhoBytes <= 0) {
            throw new IllegalArgumentException("Vídeo vazio: " + tamanhoBytes + " bytes");
        }
        if (dono == null) {
            throw new IllegalArgumentException("Dono do vídeo é obrigatório");
        }
        var video = new Video(UUID.randomUUID(), nome.trim(), tamanhoBytes, dono, Instant.now());
        video.estado = EstadoVideo.RECEBIDO;
        return video;
    }

    /**
     * Video vindo do banco. Aceita qualquer estado valido e nao roda invariantes de criacao.
     * Publica porque o adapter esta em outro pacote — vazamento assumido, o preco de Clean
     * Architecture em Java sem JPMS.
     */
    public static Video reconstituir(UUID id,
                                     String nome,
                                     long tamanhoBytes,
                                     Dono dono,
                                     String chaveVideo,
                                     EstadoVideo estado,
                                     Instant recebidoEm,
                                     Instant finalizadoEm,
                                     String chavePacote,
                                     Integer quantidadeFrames,
                                     Long tamanhoPacoteBytes,
                                     MotivoFalha motivo) {
        var video = new Video(id, nome, tamanhoBytes, dono, recebidoEm);
        video.chaveVideo = chaveVideo;
        video.estado = estado;
        video.finalizadoEm = finalizadoEm;
        video.chavePacote = chavePacote;
        video.quantidadeFrames = quantidadeFrames;
        video.tamanhoPacoteBytes = tamanhoPacoteBytes;
        video.motivo = motivo;
        return video;
    }

    /** Fecha a criacao: o objeto ja esta no armazenamento e o Video pode virar linha. */
    public Video armazenadoEm(String chaveVideo) {
        if (chaveVideo == null || chaveVideo.isBlank()) {
            throw new IllegalArgumentException("Chave do vídeo armazenado é obrigatória");
        }
        this.chaveVideo = chaveVideo;
        return this;
    }

    /**
     * A Extracao comecou. Devolve false para reentrega fora de ordem, que e caminho
     * esperado e termina em ack — nao em excecao (ADR 0002).
     */
    public boolean marcaComoIniciada() {
        if (!estado.transitaPara(EstadoVideo.PROCESSANDO)) {
            return false;
        }
        this.estado = EstadoVideo.PROCESSANDO;
        return true;
    }

    public boolean marcaComoConcluida(Instant concluidaEm,
                                      String chavePacote,
                                      int quantidadeFrames,
                                      long tamanhoPacoteBytes) {
        if (!estado.transitaPara(EstadoVideo.CONCLUIDO)) {
            return false;
        }
        this.estado = EstadoVideo.CONCLUIDO;
        this.finalizadoEm = concluidaEm;
        this.chavePacote = chavePacote;
        this.quantidadeFrames = quantidadeFrames;
        this.tamanhoPacoteBytes = tamanhoPacoteBytes;
        return true;
    }

    public boolean marcaComoFalha(Instant falhouEm, MotivoFalha motivo) {
        if (!estado.transitaPara(EstadoVideo.FALHOU)) {
            return false;
        }
        this.estado = EstadoVideo.FALHOU;
        this.finalizadoEm = falhouEm;
        this.motivo = motivo;
        return true;
    }

    /** Ha Pacote a baixar? So o CONCLUIDO produziu um; o resto e "ainda nao". */
    public boolean temPacote() {
        return estado == EstadoVideo.CONCLUIDO;
    }

    public UUID id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public long tamanhoBytes() {
        return tamanhoBytes;
    }

    public Dono dono() {
        return dono;
    }

    public String chaveVideo() {
        return chaveVideo;
    }

    public EstadoVideo estado() {
        return estado;
    }

    public Instant recebidoEm() {
        return recebidoEm;
    }

    public Instant finalizadoEm() {
        return finalizadoEm;
    }

    public String chavePacote() {
        return chavePacote;
    }

    public Integer quantidadeFrames() {
        return quantidadeFrames;
    }

    public Long tamanhoPacoteBytes() {
        return tamanhoPacoteBytes;
    }

    public MotivoFalha motivo() {
        return motivo;
    }
}
