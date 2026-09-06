#!/usr/bin/env bash
# Ticket 056: prova a primeira subida com os consumidores atrasados.
# Usa um projeto e volumes próprios e nunca executa `down -v`.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"
for ferramenta in docker curl jq; do
    command -v "$ferramenta" >/dev/null || { echo "$ferramenta ausente" >&2; exit 1; }
done

trabalho="$(mktemp -d)"
trap 'rm -rf "$trabalho"' EXIT
export COMPOSE_PROJECT_NAME="${FIAPX_PRIMEIRO_BOOT_PROJETO:-fiapx-primeiro-boot}"
export COMPOSE_FILE="$raiz/docker-compose.yml:$trabalho/portas.yml"
cat > "$trabalho/portas.yml" <<'YAML'
services:
  videos:
    ports: !override ["28080:8080"]
  keycloak:
    ports: !override ["28081:8080"]
  rabbitmq:
    ports: !override ["25673:15672"]
  minio:
    ports: !override ["29001:9001"]
  mailhog:
    ports: !override ["28025:8025"]
YAML

videos_url=http://localhost:28080
keycloak_url=http://localhost:28081
mailhog_url=http://localhost:28025
falha() { echo "FALHOU: $*" >&2; exit 1; }
espera_saude() {
    local servico="$1" inicio=$SECONDS
    until docker compose ps "$servico" --format '{{.Health}}' | grep -q '^healthy$'; do
        (( SECONDS - inicio < 180 )) || falha "$servico nao ficou saudavel"
        sleep 2
    done
}
broker() { curl -fsS --max-time 10 -u fiapx:fiapx "http://localhost:25673/api/$1"; }

echo 'Subindo broker e borda; extracao e notificacao permanecem desligados'
docker compose up -d postgres rabbitmq minio keycloak mailhog minio-seed videos
espera_saude rabbitmq
espera_saude videos

# A prova de provisionamento antecede qualquer consumidor: todos os destinos do contrato
# precisam existir antes do primeiro publish do videos.
broker definitions | jq -e '
    ([.exchanges[].name] | index("fiapx.comandos")) and
    ([.exchanges[].name] | index("fiapx.eventos")) and
    ([.queues[].name] | index("extracao.extrair")) and
    ([.queues[].name] | index("videos.extracao-iniciada")) and
    ([.queues[].name] | index("videos.extracao-concluida")) and
    ([.queues[].name] | index("videos.extracao-falhou")) and
    ([.queues[].name] | index("notificacao.video-falhou")) and
    ([.bindings[] | select(.source == "fiapx.comandos" and .destination == "extracao.extrair" and .routing_key == "extracao.extrair")] | length == 1) and
    ([.bindings[] | select(.source == "fiapx.eventos" and .destination == "notificacao.video-falhou" and .routing_key == "video.falhou")] | length == 1)' \
    >/dev/null || falha 'topologia do primeiro boot incompleta'
echo 'Topologia de comandos e eventos ja roteavel antes dos consumidores'

token="$(curl -fsS "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos -d username=demo -d password=demo \
    | jq -er .access_token)"
codigo="$(curl -sS --max-time 60 -o "$trabalho/envio.json" -w '%{http_code}' \
    "$videos_url/videos" -H "Authorization: Bearer $token" \
    -F 'arquivo=@extracao/src/test/resources/fixtures/video-valido.mp4;type=video/mp4')"
[[ "$codigo" == 202 ]] || falha "upload durante o primeiro boot devolveu HTTP $codigo"
id="$(jq -er .id "$trabalho/envio.json")"
echo "Video aceito antes dos workers: $id"

echo 'Liberando os consumidores'
docker compose up -d extracao notificacao
espera_saude extracao
espera_saude notificacao

inicio=$SECONDS
while :; do
    estado="$(curl -fsS "$videos_url/videos/$id" -H "Authorization: Bearer $token" | jq -r .estado)"
    [[ "$estado" == CONCLUIDO ]] && break
    [[ "$estado" == FALHOU ]] && falha "Video terminou em FALHOU"
    (( SECONDS - inicio < 180 )) || falha "Video ficou em $estado"
    sleep 2
done
echo "Video $id chegou a CONCLUIDO depois da liberacao dos workers"

echo 'Parando notificacao e enviando uma falha definitiva'
docker compose stop notificacao
cp extracao/src/test/resources/fixtures/arquivo-invalido.txt "$trabalho/quebrado.mp4"
codigo="$(curl -sS --max-time 60 -o "$trabalho/envio-falha.json" -w '%{http_code}' \
    "$videos_url/videos" -H "Authorization: Bearer $token" \
    -F "arquivo=@$trabalho/quebrado.mp4;type=video/mp4")"
[[ "$codigo" == 202 ]] || falha "upload invalido com notificacao atrasada devolveu HTTP $codigo"
id_falha="$(jq -er .id "$trabalho/envio-falha.json")"
inicio=$SECONDS
while :; do
    estado="$(curl -fsS "$videos_url/videos/$id_falha" -H "Authorization: Bearer $token" | jq -r .estado)"
    [[ "$estado" == FALHOU ]] && break
    [[ "$estado" == CONCLUIDO ]] && falha 'arquivo invalido chegou a CONCLUIDO'
    (( SECONDS - inicio < 180 )) || falha "falha definitiva ficou em $estado"
    sleep 2
done
echo "Video invalido $id_falha chegou a FALHOU com notificacao parada"

docker compose start notificacao
espera_saude notificacao
inicio=$SECONDS
while :; do
    mensagens="$(curl -fsS "$mailhog_url/api/v2/messages?limit=200")"
    if jq -e --arg id "$id_falha" '[.items[] | select(.Content.Body | contains($id))] | length > 0' \
        <<< "$mensagens" >/dev/null; then
        break
    fi
    (( SECONDS - inicio < 180 )) || falha "e-mail de $id_falha nao chegou"
    sleep 2
done
echo "E-mail de $id_falha chegou depois da liberacao da notificacao"

# O script deixa a stack disponível para inspeção, como o ensaio de persistência do ticket 044.
echo "Ensaio concluido. Stack: COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME"
