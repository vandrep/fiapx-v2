package br.com.fiapx.extracao.framework.dispatcher;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Quebra so o canal de saida {@code extracao-falhou} (exchange inexistente, sem declara-la)
 * para o {@link ExtracaoEstacionamentoTest} — o mesmo mecanismo que
 * {@code scripts/carga/conservacao.sh} (modo {@code mata-publicacao}) usa contra o Compose
 * (ticket 029). Um {@code @TestProfile} isola isto: nenhum outro {@code @QuarkusTest} do
 * modulo herda o canal quebrado.
 */
public class CanalExtracaoFalhouQuebradoProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "mp.messaging.outgoing.extracao-falhou.exchange.name", "extracao.eventos.inexistente",
                "mp.messaging.outgoing.extracao-falhou.exchange.declare", "false");
    }
}
