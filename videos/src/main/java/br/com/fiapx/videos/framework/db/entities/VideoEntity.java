package br.com.fiapx.videos.framework.db.entities;

import br.com.fiapx.videos.core.entities.EstadoVideo;
import br.com.fiapx.videos.core.entities.MotivoFalha;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A unica tabela do sistema. Espelha {@code docker/postgres/init.sql}, que e entregavel de
 * banca: em %prod a estrategia e {@code validate}, entao divergencia entre os dois derruba o
 * servico no boot.
 *
 * <p><b>Nao estende PanacheEntity</b> de proposito, divergindo do exemplo do template:
 * aquele fixa {@code id} como Long sequencial, e o contrato exige o UUID gerado no upload,
 * que e o mesmo id que correlaciona todas as mensagens.
 */
@Entity
@Table(name = "video")
public class VideoEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "dono_sub", nullable = false, length = 255)
    public String donoSub;

    @Column(name = "email_dono", nullable = false, length = 320)
    public String emailDono;

    @Column(name = "nome", nullable = false, length = 255)
    public String nome;

    @Column(name = "tamanho_bytes", nullable = false)
    public long tamanhoBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    public EstadoVideo estado;

    @Column(name = "recebido_em", nullable = false)
    public Instant recebidoEm;

    /** Instante terminal, de CONCLUIDO ou de FALHOU; sai como {@code concluidoEm} na API. */
    @Column(name = "finalizado_em")
    public Instant finalizadoEm;

    @Column(name = "chave_video", nullable = false, length = 1024)
    public String chaveVideo;

    @Column(name = "chave_pacote", length = 1024)
    public String chavePacote;

    @Column(name = "quantidade_frames")
    public Integer quantidadeFrames;

    @Column(name = "tamanho_pacote_bytes")
    public Long tamanhoPacoteBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo", length = 30)
    public MotivoFalha motivo;

    /**
     * Marcas de publicacao: a tabela `video` <b>e</b> o outbox (ADR 0003). Quem as le e
     * escreve e o ticket 017; elas sao mapeadas aqui porque em %prod o {@code validate}
     * derruba o servico no boot se as colunas do script nao tiverem contrapartida.
     */
    @Column(name = "comando_publicado_em")
    public Instant comandoPublicadoEm;

    @Column(name = "falha_publicada_em")
    public Instant falhaPublicadaEm;
}
