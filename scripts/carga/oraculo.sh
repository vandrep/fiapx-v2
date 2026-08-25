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
# Uso:
#   scripts/carga/oraculo.sh censo   <arquivo-de-ids>
#   scripts/carga/oraculo.sh amostra <arquivo-de-ids> [quantidade]
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"

censo() {
    local ids="$1"
    {
        echo "CREATE TEMP TABLE enviados (id uuid PRIMARY KEY);"
        echo "COPY enviados FROM STDIN;"
        cat "$ids"
        echo '\.'
        echo "SELECT coalesce(v.estado, 'AUSENTE'), count(*)
                FROM enviados e LEFT JOIN video v ON v.id = e.id
               GROUP BY 1 ORDER BY 1;"
    } | docker compose exec -T postgres \
            psql -U fiapx -d fiapx_videos -q -t -A -F' ' -v ON_ERROR_STOP=1
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

case "${1:-}" in
    censo)   censo "$2" ;;
    amostra) amostra "$2" "${3:-10}" ;;
    *)       echo "uso: $0 censo|amostra <arquivo-de-ids> [quantidade]" >&2; exit 2 ;;
esac
