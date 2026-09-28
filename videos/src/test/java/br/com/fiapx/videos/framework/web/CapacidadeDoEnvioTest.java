package br.com.fiapx.videos.framework.web;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A conta da recusa do ticket 108, sem Vert.x: quantos envios cabem na replica e quantos bytes
 * cabem no volume. O filtro que a aplica antes do corpo e julgado em
 * {@code RecusaPorCapacidadeTest}.
 */
class CapacidadeDoEnvioTest {

    private static final long MB = 1024 * 1024;
    private static final long TETO_DO_CORPO = 200 * MB;

    private final AtomicLong espacoLivre = new AtomicLong(10_000 * MB);

    @Test
    void recusaQuandoOsEnviosEmAndamentoChegamAoTeto() {
        var capacidade = new CapacidadeDoEnvio(2, TETO_DO_CORPO, espacoLivre::get);

        aceita(capacidade.reservar(OptionalLong.of(MB)));
        aceita(capacidade.reservar(OptionalLong.of(MB)));

        assertInstanceOf(CapacidadeDoEnvio.Recusada.class, capacidade.reservar(OptionalLong.of(MB)));
    }

    @Test
    void liberarDevolveAVagaESoContaUmaVez() {
        var capacidade = new CapacidadeDoEnvio(1, TETO_DO_CORPO, espacoLivre::get);

        var reserva = aceita(capacidade.reservar(OptionalLong.of(MB)));
        reserva.liberar();
        reserva.liberar();

        aceita(capacidade.reservar(OptionalLong.of(MB)));
        assertInstanceOf(CapacidadeDoEnvio.Recusada.class, capacidade.reservar(OptionalLong.of(MB)),
                "a segunda liberacao da mesma reserva abriu uma vaga que nao existia");
    }

    @Test
    void recusaOTamanhoDeclaradoMaiorQueOEspacoLivre() {
        espacoLivre.set(50 * MB);
        var capacidade = new CapacidadeDoEnvio(10, TETO_DO_CORPO, espacoLivre::get);

        assertInstanceOf(CapacidadeDoEnvio.Recusada.class, capacidade.reservar(OptionalLong.of(51 * MB)));
        aceita(capacidade.reservar(OptionalLong.of(50 * MB)));
    }

    /**
     * O espaco livre lido agora ainda nao desconta o que os envios em andamento vao gravar. Sem a
     * reserva, cada um passaria sozinho pela conta e os dois juntos encheriam o volume.
     */
    @Test
    void descontaOQueOsEnviosEmAndamentoAindaVaoGravar() {
        espacoLivre.set(100 * MB);
        var capacidade = new CapacidadeDoEnvio(10, TETO_DO_CORPO, espacoLivre::get);

        var primeira = aceita(capacidade.reservar(OptionalLong.of(60 * MB)));
        assertInstanceOf(CapacidadeDoEnvio.Recusada.class, capacidade.reservar(OptionalLong.of(60 * MB)));

        primeira.liberar();
        aceita(capacidade.reservar(OptionalLong.of(60 * MB)));
    }

    /** Sem {@code Content-Length}, o envio pode chegar ao teto do corpo, e e isso que ele reserva. */
    @Test
    void envioSemTamanhoDeclaradoReservaOTetoDoCorpo() {
        espacoLivre.set(TETO_DO_CORPO - 1);
        var capacidade = new CapacidadeDoEnvio(10, TETO_DO_CORPO, espacoLivre::get);

        assertInstanceOf(CapacidadeDoEnvio.Recusada.class, capacidade.reservar(OptionalLong.empty()));

        espacoLivre.set(TETO_DO_CORPO);
        aceita(capacidade.reservar(OptionalLong.empty()));
    }

    @Test
    void tetoDerivadoDoTamanhoDoVolumeEDoTetoDoCorpo() {
        assertEquals(5, CapacidadeDoEnvio.tetoDerivado(1024 * MB, TETO_DO_CORPO));
        assertEquals(1, CapacidadeDoEnvio.tetoDerivado(TETO_DO_CORPO - 1, TETO_DO_CORPO),
                "um volume menor que um corpo ainda recebe um envio; o espaco livre decide o resto");
    }

    private static CapacidadeDoEnvio.Reserva aceita(CapacidadeDoEnvio.Decisao decisao) {
        return assertInstanceOf(CapacidadeDoEnvio.Aceita.class, decisao).reserva();
    }
}
