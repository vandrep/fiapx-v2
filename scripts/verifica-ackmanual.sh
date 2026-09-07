#!/usr/bin/env bash
# Cobra que as tres copias de AckManual sejam identicas fora da linha `package`.
#
# AckManual e a segunda excecao explicita da secao "As copias deliberadas entre
# servicos" do AGENTS.md: ao contrario das outras quatro familias, as tres copias
# foram desenhadas para ser identicas e nada no desenho sugere divergencia local
# legitima. Por isso ganham guarda automatica, como o ArchitectureConstraintsTest.
#
# Roda na fase validate do agregador, nao dos modulos: o invariante e do repositorio
# inteiro, e nenhum modulo deve enxergar o diretorio do vizinho.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
servicos=(videos extracao notificacao)

caminho_ackmanual() {
    local servico="$1"
    echo "$raiz/$servico/src/main/java/br/com/fiapx/$servico/framework/dispatcher/AckManual.java"
}

normalizar() {
    sed -E 's/^package [a-zA-Z0-9_.]+;$/package X;/' "$1"
}

referencia_servico="${servicos[0]}"
referencia="$(caminho_ackmanual "$referencia_servico")"
[[ -f "$referencia" ]] || { echo "ERRO: nao encontrei $referencia" >&2; exit 1; }

divergentes=()
for servico in "${servicos[@]:1}"; do
    copia="$(caminho_ackmanual "$servico")"
    [[ -f "$copia" ]] || { echo "ERRO: nao encontrei $copia" >&2; exit 1; }
    diff -q <(normalizar "$referencia") <(normalizar "$copia") >/dev/null || divergentes+=("$servico")
done

if (( ${#divergentes[@]} > 0 )); then
    cat >&2 <<MSG
ERRO: AckManual divergiu em: ${divergentes[*]}

As tres copias precisam ser identicas fora da linha "package" — nada no desenho sugere
que devam divergir. Uma mudanca na parte comum precisa ser aplicada nas tres.

Para ver o que mudou:
MSG
    for servico in "${divergentes[@]}"; do
        echo "    diff $referencia $(caminho_ackmanual "$servico")" >&2
    done
    exit 1
fi

echo "AckManual identico nos tres servicos fora do package: ${servicos[*]}"
