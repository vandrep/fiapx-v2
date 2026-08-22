#!/usr/bin/env bash
# Cobra que as tres copias de ArchitectureConstraintsTest sejam byte a byte identicas.
#
# Nao ha modulo test-support (ver AGENTS.md): cada servico carrega a sua propria copia do
# teste arquitetural, para que ele continue legivel no lugar, ao lado do codigo que julga.
# O preco disso e a divergencia silenciosa — alguem relaxa uma regra num servico e os
# outros dois seguem afrouxados sem que ninguem veja. Esta guarda e o que paga esse preco.
#
# Roda na fase validate do agregador, nao dos modulos: o invariante e do repositorio
# inteiro, e nenhum modulo deve enxergar o diretorio do vizinho.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
caminho="src/test/java/br/com/fiapx/architecture/ArchitectureConstraintsTest.java"
servicos=(videos extracao notificacao)

referencia="$raiz/${servicos[0]}/$caminho"
[[ -f "$referencia" ]] || { echo "ERRO: nao encontrei $referencia" >&2; exit 1; }

divergentes=()
for servico in "${servicos[@]:1}"; do
    copia="$raiz/$servico/$caminho"
    [[ -f "$copia" ]] || { echo "ERRO: nao encontrei $copia" >&2; exit 1; }
    cmp -s "$referencia" "$copia" || divergentes+=("$servico")
done

if (( ${#divergentes[@]} > 0 )); then
    cat >&2 <<MSG
ERRO: ArchitectureConstraintsTest divergiu em: ${divergentes[*]}

As tres copias precisam ser identicas — inclusive MODULO_DO_SERVICO, que e derivado do
diretorio do modulo justamente para nao divergir. Uma regra relaxada num servico precisa
ser relaxada nos tres, ou nao ser relaxada.

Para ver o que mudou:
MSG
    for servico in "${divergentes[@]}"; do
        echo "    diff ${servicos[0]}/$caminho $servico/$caminho" >&2
    done
    exit 1
fi

echo "ArchitectureConstraintsTest identico nos tres servicos: ${servicos[*]}"
