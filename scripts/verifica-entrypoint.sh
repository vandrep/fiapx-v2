#!/usr/bin/env bash
# Cobra que as tres copias de `entrypoint.sh` (ticket 096) sejam byte a byte identicas.
#
# O script vive em tres copias porque o contexto de build de cada imagem e o diretorio do
# proprio servico (`.github/workflows/ci.yml`), e um `COPY` nao alcanca fora dele. Nada no
# desenho sugere divergencia local legitima — as tres fazem a mesma pergunta a DNS do Docker e
# chamam o mesmo `quarkus-run.jar` —, entao a guarda e do mesmo tipo da do
# ArchitectureConstraintsTest: comparacao sem normalizacao.
#
# Roda na fase validate do agregador, nao dos modulos: o invariante e do repositorio
# inteiro, e nenhum modulo deve enxergar o diretorio do vizinho.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
servicos=(videos extracao notificacao)

referencia="$raiz/${servicos[0]}/entrypoint.sh"
[[ -f "$referencia" ]] || { echo "ERRO: nao encontrei $referencia" >&2; exit 1; }

divergentes=()
for servico in "${servicos[@]:1}"; do
    copia="$raiz/$servico/entrypoint.sh"
    [[ -f "$copia" ]] || { echo "ERRO: nao encontrei $copia" >&2; exit 1; }
    cmp -s "$referencia" "$copia" || divergentes+=("$servico")
done

if (( ${#divergentes[@]} > 0 )); then
    cat >&2 <<MSG
ERRO: entrypoint.sh divergiu em: ${divergentes[*]}

As tres copias precisam ser identicas byte a byte. Uma mudanca precisa ser aplicada nas tres.

Para ver o que mudou:
MSG
    for servico in "${divergentes[@]}"; do
        echo "    diff $referencia $raiz/$servico/entrypoint.sh" >&2
    done
    exit 1
fi

echo "entrypoint.sh identico nos tres servicos: ${servicos[*]}"
