#!/usr/bin/env bash
# Ticket 107: prova de ponta a ponta do resgate contra o Compose de verdade.
#
# Forca dois Videos a presos do jeito que um Video se perde: o comando sai, a marca e gravada e
# a mensagem some (aqui, purga de `extracao.extrair` com o `extracao` parado). Um fica em
# RECEBIDO; o outro e posto em PROCESSANDO por SQL, que e o estado de quem perdeu a Extracao no
# meio. Prova que a varredura NAO os toca sem resgate, que `scripts/resgata-video.sh` os leva a
# CONCLUIDO, e que ele recusa Video com desfecho.
#
# Usa o Compose padrao e a imagem que estiver com a tag dele; para provar codigo local,
# construa a imagem do `videos` antes. Para e purga `extracao.extrair`: nao rode com trabalho
# de verdade na fila. Deixa a stack de pe.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
rabbitmq_url="${FIAPX_RABBITMQ_URL:-http://localhost:15672}"
fixture_valido="extracao/src/test/resources/fixtures/video-valido.mp4"
fixture_invalido="extracao/src/test/resources/fixtures/arquivo-invalido.txt"

for ferramenta in docker curl jq; do
    command -v "$ferramenta" >/dev/null || { echo "$ferramenta ausente" >&2; exit 1; }
done
trabalho="$(mktemp -d)"
# Nao deixa o `extracao` parado se o script cair no meio.
trap 'rm -rf "$trabalho"; docker compose start extracao >/dev/null 2>&1 || true' EXIT

passo() { echo; echo "==> $*"; }
ok()    { echo "    OK  $*"; }
falha() { echo "    FALHOU  $*" >&2; exit 1; }
espera() {
    local teto=$1; shift
    local inicio=$SECONDS
    until "$@"; do
        (( SECONDS - inicio < teto )) || falha "timeout de ${teto}s: $*"
        sleep 2
    done
}
saudavel() {
    local estados
    estados="$(docker compose ps "$1" --format '{{.Health}}')"
    [[ -n "$estados" ]] && ! grep -qv '^healthy$' <<< "$estados"
}
sql() { docker compose exec -T postgres psql -U fiapx -d fiapx_videos -v ON_ERROR_STOP=1 -At -c "$1"; }
broker() { curl -fsS --max-time 10 -u fiapx:fiapx "$@"; }
fila_sem_consumidor() {
    broker "$rabbitmq_url/api/queues/%2F/extracao.extrair" | jq -e '.consumers == 0' >/dev/null
}
fila_com() {
    broker "$rabbitmq_url/api/queues/%2F/extracao.extrair" | jq -e --argjson n "$1" '.messages == $n' >/dev/null
}
marcado() { [[ "$(sql "select comando_publicado_em is not null from video where id = '$1'")" == t ]]; }
estado() { sql "select estado from video where id = '$1'"; }
chegou_a() { [[ "$(estado "$1")" == "$2" ]]; }
falha_publicada() {
    [[ "$(sql "select estado = 'FALHOU' and falha_publicada_em is not null from video where id = '$1'")" == t ]]
}
# O `.mp4` e o `type` sao da borda, que so aceita video; o invalido e barrado pelo `extracao`.
enviar() {
    local codigo
    cp "$1" "$trabalho/envio.mp4"
    codigo="$(curl -sS --max-time 60 -o "$trabalho/envio.json" -w '%{http_code}' \
        "$videos_url/videos" -H "Authorization: Bearer $token" \
        -F "arquivo=@$trabalho/envio.mp4;type=video/mp4")"
    [[ "$codigo" == 202 ]] || falha "envio de $1: HTTP $codigo"
    jq -er '.id' "$trabalho/envio.json"
}
resgatar() {
    local saida=0
    scripts/resgata-video.sh "$1" > "$trabalho/resgate.out" 2>&1 || saida=$?
    sed 's/^/      | /' "$trabalho/resgate.out"
    [[ "$saida" == "$2" ]] || falha "resgata-video.sh $1 saiu com $saida, esperava $2"
}

# ---------------------------------------------------------------------------------------
passo "1. Compose de pe"
docker compose up -d >/dev/null 2>&1
for servico in videos extracao notificacao; do espera 180 saudavel "$servico"; done
token="$(curl -fsS --max-time 10 "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos -d username=demo -d password=demo \
    | jq -er .access_token)"
ok "videos, extracao e notificacao saudaveis; token obtido"

# ---------------------------------------------------------------------------------------
passo "2. Dois Videos presos: comando publicado e marcado, mensagem perdida"
docker compose stop extracao >/dev/null 2>&1
espera 60 fila_sem_consumidor
fila_com 0 || falha "extracao.extrair tem mensagens antes do envio; nao purgo trabalho alheio"
recebido="$(enviar "$fixture_valido")"
processando="$(enviar "$fixture_valido")"
espera 60 marcado "$recebido"
espera 60 marcado "$processando"
espera 30 fila_com 2
broker -X DELETE "$rabbitmq_url/api/queues/%2F/extracao.extrair/contents"
espera 30 fila_com 0
sql "update video set estado = 'PROCESSANDO', iniciada_em = now() where id = '$processando'" >/dev/null
docker compose start extracao >/dev/null 2>&1
espera 180 saudavel extracao
ok "RECEBIDO $recebido e PROCESSANDO $processando, ambos com marca e sem mensagem"

# ---------------------------------------------------------------------------------------
passo "3. Sem resgate, a varredura nao os toca"
# Folga de 1 min contra o crash, mais uma passada de 30 s e margem: depois disso uma varredura
# que ignorasse a marca ja teria republicado.
recebido_ha="$(sql "select extract(epoch from now() - recebido_em)::int from video where id = '$recebido'")"
sleep $(( recebido_ha < 100 ? 100 - recebido_ha : 0 ))
chegou_a "$recebido" RECEBIDO || falha "RECEBIDO andou sem resgate: $(estado "$recebido")"
chegou_a "$processando" PROCESSANDO || falha "PROCESSANDO andou sem resgate: $(estado "$processando")"
fila_com 0 || falha "alguem republicou sem resgate"
ok "passada a folga e uma varredura, os dois seguem presos"

# ---------------------------------------------------------------------------------------
passo "4. Resgate leva os dois a CONCLUIDO"
resgatar "$recebido" 0
resgatar "$processando" 0
espera 180 chegou_a "$recebido" CONCLUIDO
espera 180 chegou_a "$processando" CONCLUIDO
marcado "$recebido" || falha "a republicacao nao regravou a marca de $recebido"
marcado "$processando" || falha "a republicacao nao regravou a marca de $processando"
ok "os dois em CONCLUIDO, com a marca regravada pela varredura"

# ---------------------------------------------------------------------------------------
passo "5. Recusas"
resgatar "$recebido" 2
invalido="$(enviar "$fixture_invalido")"
espera 180 falha_publicada "$invalido"
resgatar "$invalido" 2
resgatar "$(cat /proc/sys/kernel/random/uuid)" 3
resgatar "nao-e-uuid" 1
ok "CONCLUIDO e FALHOU com aviso recusados; id inexistente e id invalido tambem"

echo
echo "Resgate provado de ponta a ponta."
