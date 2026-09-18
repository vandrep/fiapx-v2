#!/bin/sh
# Ponto de entrada das tres imagens (ticket 096). As tres copias sao byte a byte identicas, e
# `scripts/verifica-entrypoint.sh` reprova o build na primeira divergencia.
#
# Declara no recurso OpenTelemetry o nome que o Compose deu a este container
# (`fiapx-v2-extracao-2`), como `container.name`. E dele que o coletor tira o numero da replica
# para montar a etiqueta `instance` (`fiapx-extracao-2`), que e o que as legendas do *JVM
# Overview* mostram. O id do container (`host.name`) e unico mas ilegivel, e nenhum outro
# atributo diz qual replica e qual: o nome so existe do lado de fora, no Docker, e a unica
# porta para ele de dentro do container e a DNS embutida (127.0.0.11), que responde o PTR do
# proprio IP com `<nome>.<rede>`. O `/etc/hosts` nao serve: mapeia o IP para o id.
#
# Fora do Compose, ou se a DNS nao responder em 3 s, nada e declarado e o coletor cai no
# `<service.name>/<host.name>` de antes. A falha aqui nunca impede o servico de subir.
nome=$(timeout 3 nslookup "$(hostname -i | cut -d' ' -f1)" 2>/dev/null | awk '/name = / {print $NF; exit}')
nome=${nome%%.*}

if [ -n "$nome" ]; then
    QUARKUS_OTEL_RESOURCE_ATTRIBUTES="${QUARKUS_OTEL_RESOURCE_ATTRIBUTES:+$QUARKUS_OTEL_RESOURCE_ATTRIBUTES,}container.name=$nome"
    export QUARKUS_OTEL_RESOURCE_ATTRIBUTES
fi

# `exec`, e nao uma chamada comum: o JVM precisa ser o PID 1 para receber o SIGTERM do
# `docker stop` — e dele que depende o dreno do `extracao` (ticket 035).
exec java -jar /app/quarkus-run.jar "$@"
