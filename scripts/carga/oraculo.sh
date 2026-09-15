#!/usr/bin/env bash
# Oraculo do harness de carga (ticket 025): responde o que o *sistema* fez, contra a lista de
# ids que o injetor conseguiu enviar. Dois papeis, de proposito separados:
#
#   censo    SELECT ... GROUP BY estado, direto no Postgres. E o que roda em laco durante a
#            drenagem, porque um oraculo que perturba o experimento nao e oraculo: 500 Videos
#            pela API paginada somariam dezenas de requisicoes ao sistema sob medicao.
#
#   amostra  Confere pela API, como dono, um punhado de ids sorteados. A garantia do
#            enunciado e sobre o que o *usuario* recebe, e uma linha CONCLUIDO que o
#            `GET /videos` nao devolve e uma requisicao perdida do mesmo jeito. Amostra, e
#            nao censo, exatamente para nao virar carga.
#
# O censo e sempre um LEFT JOIN a partir da lista de envios: o estado `AUSENTE` — aceito com
# 202 e sem linha no banco — e a perda que um `SELECT count(*) FROM video` jamais mostraria.
#
# Mais duas consultas ao Postgres, so do modo mata-extracao, para o ticket 113: `presos`, a
# contagem do gauge de Video preso, e `interrompidas`, o destino das tentativas mortas pelo kill.
#
# Uso:
#   scripts/carga/oraculo.sh censo   <arquivo-de-ids>
#   scripts/carga/oraculo.sh amostra <arquivo-de-ids> [quantidade]
#   scripts/carga/oraculo.sh presos
#   scripts/carga/oraculo.sh interrompidas <aceitos> <interrompidos> <instante-do-kill>
set -euo pipefail

psql_videos() {
    docker compose exec -T postgres psql -U fiapx -d fiapx_videos -q -t -A -F' ' -v ON_ERROR_STOP=1
}

# Carrega um arquivo de ids numa tabela temporaria, para o LEFT JOIN a partir da lista.
tabela_de_ids() {
    local tabela="$1" ids="$2"
    echo "CREATE TEMP TABLE $tabela (id uuid PRIMARY KEY);"
    echo "COPY $tabela FROM STDIN;"
    cat "$ids"
    echo '\.'
}

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"

censo() {
    local ids="$1"
    {
        tabela_de_ids enviados "$ids"
        echo "SELECT coalesce(v.estado, 'AUSENTE'), count(*)
                FROM enviados e LEFT JOIN video v ON v.id = e.id
               GROUP BY 1 ORDER BY 1;"
    } | psql_videos
}

# Token proprio, e nao o do injetor: o oraculo roda *depois* da drenagem, quando o token da
# rajada ja morreu (5 min de vida).
token() {
    curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=fiapx-videos \
        -d "username=$usuario" -d "password=$senha" | jq -r '.access_token // empty'
}

amostra() {
    local ids="$1" quantidade="${2:-10}"
    local tok divergencias=0

    tok="$(token)"
    [[ -n "$tok" ]] || { echo "Keycloak nao devolveu token para $usuario" >&2; return 1; }

    while read -r id; do
        local corpo estado codigo
        corpo="$(curl -sS -o /tmp/oraculo-amostra.json -w '%{http_code}' \
            "$videos_url/videos/$id" -H "Authorization: Bearer $tok")"
        codigo="$corpo"
        estado="$(jq -r '.estado // "—"' /tmp/oraculo-amostra.json)"
        if [[ "$codigo" != 200 || ( "$estado" != CONCLUIDO && "$estado" != FALHOU ) ]]; then
            echo "    DIVERGENCIA  $id  HTTP $codigo  estado $estado"
            divergencias=$((divergencias + 1))
        else
            echo "    ok           $id  $estado"
        fi
    done < <(shuf -n "$quantidade" "$ids")

    # Uma requisicao a mais, e ela cobre o outro lado da mesma duvida: a listagem paginada
    # e um caminho de consulta diferente do GET por id, e e o que o usuario abre primeiro.
    local total
    total="$(curl -sS "$videos_url/videos?tamanho=1" -H "Authorization: Bearer $tok" | jq -r '.total')"
    echo "    GET /videos informa total=$total para $usuario"

    return $(( divergencias > 0 ? 1 : 0 ))
}

# A contagem do gauge fiapx.videos.presos{estado="PROCESSANDO"} (ticket 106), feita direto no
# Postgres porque o overlay de carga desliga a observabilidade (ticket 113). E o mesmo predicado
# do ContarVideosPresosUseCase, sobre TODOS os Videos e nao so os da rodada, como o gauge. A
# segunda coluna e a idade do PROCESSANDO mais velho, em segundos: e ela que mostra quanto falta
# para o limiar numa corrida curta, em que a contagem fica em zero. Os 30 min sao o
# fiapx.deteccao.limiar-de-video-preso do videos; se ele mudar, este muda junto.
presos() {
    psql_videos <<SQL
SELECT count(*) FILTER (WHERE iniciada_em < now() - interval '30 minutes'),
       coalesce(round(extract(epoch FROM now() - min(iniciada_em))), 0)
  FROM video WHERE estado = 'PROCESSANDO';
SQL
}

# Uma linha por Video cuja tentativa foi interrompida (ticket 113): estado, iniciada_em ->
# desfecho em segundos, e a posicao em que a entrega voltou a fila. A posicao e contada pelos
# Videos da rodada cuja PRIMEIRA tentativa comecou depois do kill e antes do desfecho do
# interrompido: entrega recolocada no comeco fica perto de zero (so os que as outras replicas ja
# tinham pego), no fim fica perto das mensagens prontas no instante do kill. A contagem inclui o
# que as outras replicas pegaram durante a propria reentrega, entao erra para cima.
interrompidas() {
    local aceitos="$1" interrompidos="$2" instante_kill="$3"
    {
        tabela_de_ids enviados "$aceitos"
        tabela_de_ids interrompidos "$interrompidos"
        echo "SELECT i.id, coalesce(v.estado, 'AUSENTE'),
                     round(extract(epoch FROM v.finalizado_em - v.iniciada_em)),
                     (SELECT count(*) FROM enviados e JOIN video o ON o.id = e.id
                       WHERE o.id <> i.id
                         AND o.iniciada_em > '$instante_kill'::timestamptz
                         AND o.iniciada_em < v.finalizado_em)
                FROM interrompidos i LEFT JOIN video v ON v.id = i.id
               ORDER BY 1;"
    } | psql_videos
}

case "${1:-}" in
    censo)         censo "$2" ;;
    amostra)       amostra "$2" "${3:-10}" ;;
    presos)        presos ;;
    interrompidas) interrompidas "$2" "$3" "$4" ;;
    *)             echo "uso: $0 censo|amostra <arquivo-de-ids> [quantidade] | presos | interrompidas <aceitos> <interrompidos> <instante-do-kill>" >&2; exit 2 ;;
esac
