#!/usr/bin/env bash
# Ticket 044: ensaio real de down/up, em projeto e portas separados da demo.
# Deixa a stack de ensaio e seus volumes para inspecao; nunca executa down -v.
set -euo pipefail
raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"
for ferramenta in docker curl jq diff grep unzip; do
    command -v "$ferramenta" >/dev/null || { echo "$ferramenta ausente" >&2; exit 1; }
done
trabalho="$(mktemp -d)"
trap 'rm -rf "$trabalho"' EXIT
export COMPOSE_PROJECT_NAME=fiapx-persistencia
export COMPOSE_FILE="$raiz/docker-compose.yml:$trabalho/portas.yml"
cat > "$trabalho/portas.yml" <<'YAML'
services:
  videos:
    ports: !override ["18080:8080"]
  keycloak:
    ports: !override ["18081:8080"]
  rabbitmq:
    ports: !override ["25672:15672"]
  minio:
    ports: !override ["19001:9001"]
  mailhog:
    ports: !override ["18025:8025"]
YAML
export FIAPX_VIDEOS_URL=http://localhost:18080
export FIAPX_KEYCLOAK_URL=http://localhost:18081
export FIAPX_MAILHOG_URL=http://localhost:18025
falha() { echo "FALHOU: $*" >&2; exit 1; }
espera() {
    local inicio=$SECONDS
    until "$@"; do
        (( SECONDS - inicio < 180 )) || falha "timeout: $*"
        sleep 2
    done
}
# Uma linha por container, e nao por servico: o `extracao` sobe com duas replicas desde o
# ticket 049. Saudavel e "existe container e nenhum deles esta fora de healthy".
saudavel() {
    local estados
    estados="$(docker compose ps "$1" --format '{{.Health}}')"
    [[ -n "$estados" ]] && ! grep -qv '^healthy$' <<< "$estados"
}
broker() { curl -fsS --max-time 10 -u fiapx:fiapx "http://localhost:25672/api/$1"; }
token_demo() {
    token="$(curl -fsS --max-time 10 "$FIAPX_KEYCLOAK_URL/realms/fiapx/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=fiapx-videos -d username=demo -d password=demo \
        | jq -er .access_token)"
}
# O exchange interno de log pode aparecer apenas depois do primeiro reinicio.
# Ele nao pertence ao contrato de mensagens da aplicacao.
topologia() {
    broker definitions | jq -S '{queues, exchanges, bindings, policies} |
        .queues |= sort_by(.name) | .exchanges |= (map(select(.name != "amq.rabbitmq.log")) | sort_by(.name)) |
        .bindings |= sort_by(.source, .destination, .routing_key) | .policies |= sort_by(.name)'
}
fila_pendente() {
    broker queues/%2F/extracao.extrair | jq -e \
        '.messages_ready == 3 and .messages_unacknowledged == 0 and .consumers == 0' >/dev/null
}
marcas() {
    docker compose exec -T postgres psql -U fiapx -d fiapx_videos -At \
        -c "SELECT id, comando_publicado_em FROM video WHERE id IN ($ids_sql) ORDER BY id"
}

echo 'Preparando topologia real e pausando workers antes dos envios'
docker compose up -d
for servico in videos extracao notificacao; do espera saudavel "$servico"; done
# Uma execucao anterior interrompida pode ter deixado trabalho pendente.
fila_drenada() {
    broker queues/%2F/extracao.extrair | jq -e '.messages == 0' >/dev/null
}
espera fila_drenada
docker compose stop extracao notificacao
fila_vazia() {
    broker queues/%2F/extracao.extrair | jq -e '.messages == 0 and .consumers == 0' >/dev/null
}
espera fila_vazia
token_demo
ids=()
ids_sql=''
for numero in 1 2 3; do
    codigo="$(curl -sS --max-time 60 -o "$trabalho/envio.json" -w '%{http_code}' \
        "$FIAPX_VIDEOS_URL/videos" -H "Authorization: Bearer $token" \
        -F 'arquivo=@extracao/src/test/resources/fixtures/video-valido.mp4;type=video/mp4')"
    [[ "$codigo" == 202 ]] || falha "upload $numero: HTTP $codigo"
    id="$(jq -er '.id | select(test("^[0-9a-fA-F-]{36}$"))' "$trabalho/envio.json")"
    ids+=("$id")
    ids_sql+="${ids_sql:+,}'$id'"
done
espera fila_pendente
marcas > "$trabalho/marcas-antes"
[[ "$(wc -l < "$trabalho/marcas-antes")" == 3 ]] || falha 'faltam linhas no Postgres'
! grep -E '\|$' "$trabalho/marcas-antes" || falha 'marca de publicacao ausente'
cat "$trabalho/marcas-antes"
topologia > "$trabalho/topologia-antes"
no_antes="$(broker nodes | jq -r '.[0].name')"
echo "Antes: 3 comandos prontos; no=$no_antes"
container_antes="$(docker compose ps -q rabbitmq)"

echo 'Recriando toda a stack sem excluir volumes; workers permanecem desligados'
docker compose down
docker compose up -d postgres rabbitmq
espera saudavel rabbitmq
espera saudavel postgres
[[ "$(docker compose ps -q rabbitmq)" != "$container_antes" ]] || falha 'broker nao foi recriado'
no_depois="$(broker nodes | jq -r '.[0].name')"
echo "Depois: no=$no_depois"
marcas > "$trabalho/marcas-depois"
diff -u "$trabalho/marcas-antes" "$trabalho/marcas-depois"
# Aqui nenhum servico pode republicar nem redeclarar a topologia para mascarar a perda.
espera fila_pendente
[[ "$no_antes" == "$no_depois" ]] || falha "identidade do no mudou"
topologia > "$trabalho/topologia-depois"
diff -u "$trabalho/topologia-antes" "$trabalho/topologia-depois"
echo '3 comandos preservados; marcas, filas, bindings e politicas identicos'

docker compose up -d
for servico in videos extracao notificacao; do espera saudavel "$servico"; done
token_demo
concluido() {
    curl -fsS --max-time 10 "$FIAPX_VIDEOS_URL/videos/$1" -H "Authorization: Bearer $token" \
        > "$trabalho/estado.json" && jq -e '.estado == "CONCLUIDO"' "$trabalho/estado.json" >/dev/null
}
for id in "${ids[@]}"; do
    espera concluido "$id"
    jq -c '{id, estado}' "$trabalho/estado.json"
done
marcas > "$trabalho/marcas-finais"
diff -u "$trabalho/marcas-antes" "$trabalho/marcas-finais"
echo 'Todos os Videos concluidos pela API, sem alterar marcas ou republicar manualmente'
echo 'Executando smoke ponta a ponta na mesma stack isolada'
bash scripts/smoke.sh
