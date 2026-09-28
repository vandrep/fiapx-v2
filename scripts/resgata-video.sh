#!/usr/bin/env bash
# Resgate de um Video preso (ticket 107, CONTEXT.md § Video perdido): apaga a marca de
# publicacao e deixa a varredura do ADR 0003 republicar pelo mesmo caminho do envio.
# O procedimento completo, com a purga da mensagem residual, esta em
# docs/operacao/resgate-de-video-preso.md.
#
# Uso:
#   scripts/resgata-video.sh <idVideo>
#
# Fala com o Postgres do Compose (`docker compose exec postgres`), entao respeita
# COMPOSE_PROJECT_NAME e COMPOSE_FILE de quem chama.
#
# Saidas:
#   0  resgatado, ou ja pendente e a varredura vai republicar sem ajuda
#   2  recusado: o Video tem desfecho, e terminal e terminal
#   3  Video nao encontrado
#   1  uso errado ou falha ao falar com o banco
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

id="${1:-}"
if [[ ! "$id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
    echo "uso: scripts/resgata-video.sh <idVideo>  (UUID do Video)" >&2
    exit 1
fi

# Um comando so. O `UPDATE` repete o predicado do estado: se o Video chegar a desfecho enquanto
# ele espera a linha, ele nao a toca, e a varredura tambem nao o republicaria, porque filtra pelo
# estado. A terceira coluna diz quem respondeu: a leitura usa o snapshot do inicio do comando e
# pode ainda mostrar o estado antigo, entao so `resgate` prova que a marca foi apagada.
# O `|| exit 1` nao e redundante com o `set -e`: o psql sai com 3 em erro de SQL, que e o
# codigo de "nao encontrado" daqui.
resultado="$(docker compose exec -T postgres psql -U fiapx -d fiapx_videos -v ON_ERROR_STOP=1 \
    -v id="$id" -At -F '|' <<'SQL'
WITH resgate AS (
    UPDATE video SET comando_publicado_em = NULL
    WHERE id = :'id' AND estado IN ('RECEBIDO', 'PROCESSANDO')
    RETURNING estado
)
SELECT estado, NULL::boolean, 'resgate' FROM resgate
UNION ALL
SELECT estado, falha_publicada_em IS NOT NULL, 'leitura'
FROM video
WHERE id = :'id' AND NOT EXISTS (SELECT 1 FROM resgate);
SQL
)" || { echo "Falha ao falar com o Postgres do Compose." >&2; exit 1; }

if [[ -z "$resultado" ]]; then
    echo "Video $id nao encontrado." >&2
    exit 3
fi

IFS='|' read -r estado falha_publicada origem <<< "$resultado"

case "$estado" in
    RECEBIDO|PROCESSANDO)
        if [[ "$origem" != resgate ]]; then
            echo "Video $id mudou de estado durante o resgate e nao foi tocado. Rode de novo." >&2
            exit 1
        fi
        echo "Video $id em $estado: marca do comando apagada."
        echo "A varredura republica o ExtrairVideo em ate ~30 s. O Video recomeca as tentativas."
        echo "Depois, purgue a mensagem residual: docs/operacao/resgate-de-video-preso.md."
        ;;
    FALHOU)
        if [[ "$falha_publicada" == t ]]; then
            echo "Recusado: Video $id ja e FALHOU e o VideoFalhou ja foi publicado. Terminal e terminal." >&2
            exit 2
        fi
        # Sem marca, a varredura ja o considera pendente: nao ha marca para apagar.
        echo "Video $id em FALHOU sem o VideoFalhou publicado: ja pendente, a varredura republica o aviso."
        ;;
    CONCLUIDO)
        echo "Recusado: Video $id ja e CONCLUIDO. Terminal e terminal." >&2
        exit 2
        ;;
    *)
        echo "Estado inesperado para $id: '$estado'" >&2
        exit 1
        ;;
esac
