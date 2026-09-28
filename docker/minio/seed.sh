#!/bin/sh
# Seed do MinIO para o Compose (ticket 020): cria os dois buckets e a retencao de cada um.
# Roda uma vez, como servico one-shot, depois do `minio` ficar saudavel; os tres servicos de
# negocio esperam este seed terminar (service_completed_successfully) antes de subir, porque o
# `videos` grava no bucket `videos` desde o primeiro upload.
#
# Retencao (ticket 011, emendado pelo 105 e pelo ADR 0005):
#   videos   expira 7 dias depois da criacao, mas SO o objeto com a tag desfecho=sim, que o
#            `videos` grava quando o Video chega a CONCLUIDO ou FALHOU. Sem a tag, nunca.
#   pacotes  expira 7 dias depois da criacao, sem filtro.
#
# `mc ilm import` e nao `mc ilm rule add`: o import SUBSTITUI a configuracao inteira do bucket,
# entao rodar o seed de novo nao acumula regra, e um volume que ainda traz a regra antiga sem
# filtro do bucket `videos` a perde no primeiro boot. O `rule add` sem ID acrescentava uma regra
# nova a cada execucao.
set -eu

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

mc mb --ignore-existing local/videos
mc ilm import local/videos <<'JSON'
{"Rules":[{"ID":"original-depois-do-desfecho","Status":"Enabled","Filter":{"Tag":{"Key":"desfecho","Value":"sim"}},"Expiration":{"Days":7}}]}
JSON

mc mb --ignore-existing local/pacotes
mc ilm import local/pacotes <<'JSON'
{"Rules":[{"ID":"pacote","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":7}}]}
JSON
