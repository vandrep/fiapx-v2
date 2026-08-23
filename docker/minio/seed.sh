#!/bin/sh
# Seed do MinIO para o Compose (ticket 020): cria os dois buckets e a regra de retencao de
# 7 dias (ticket 011). Roda uma vez, como servico one-shot, depois do `minio` ficar saudavel;
# os tres servicos de negocio esperam este seed terminar (service_completed_successfully)
# antes de subir, porque o `videos` grava no bucket `videos` desde o primeiro upload.
set -eu

mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

for bucket in videos pacotes; do
    mc mb --ignore-existing "local/$bucket"
    mc ilm rule add "local/$bucket" --expire-days 7
done
