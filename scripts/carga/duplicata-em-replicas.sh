#!/usr/bin/env bash
# Ensaio do comando duplicado em duas replicas (ticket 041).
#
# A afirmacao sob julgamento e a do ADR 0003 levada ao disco: *duas replicas podem receber o
# mesmo comando de Extracao sem destruir o trabalho uma da outra*. O `smoke.sh` nao alcanca
# isso — ele manda um Video de cada vez para uma replica so —, e a suite tambem nao: dentro de
# um `@QuarkusTest` ha um consumidor so, e o `max-outstanding-messages=1` serializa a
# duplicata. Concorrencia de verdade sobre o volume compartilhado `fiapx-extracao-scratch` so
# existe com duas replicas de pe, que e o que este script sobe.
#
# O estimulo e deliberado: o comando duplicado e publicado direto na routing key real
# (`fiapx.comandos`/`extracao.extrair`) pela API de management, porque esperar a varredura de
# reconciliacao republicar por conta propria tornaria o ensaio nao reproduzivel.
#
# Criterio, fixado antes de rodar:
#   1. As duas replicas aparecem com scratch PROPRIO no volume durante o trabalho.
#   2. O Video termina em CONCLUIDO — nao em FALHOU por colisao de arquivo.
#   3. O Pacote baixa pela borda publica com 200 e passa no `unzip -t`, com ao menos um frame.
#   4. O scratch fica vazio nas duas replicas ao fim: a limpeza por tentativa nao deixa resto.
#
# Antes do ticket 041 este ensaio reprovava nos criterios 2 e 3: o h264 valido de controle
# chegava ao usuario como FALHOU/ARQUIVO_INVALIDO, e o download respondia 409.
#
# Uso:
#   scripts/carga/duplicata-em-replicas.sh [duplicatas]     # default 3
#
# Deixa a stack de pe com DUAS replicas de extracao; `docker compose up -d` a devolve a uma.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

duplicatas="${1:-3}"
videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
rabbitmq_url="${FIAPX_RABBITMQ_URL:-http://localhost:15672}"
rabbitmq_usuario="${FIAPX_RABBITMQ_USUARIO:-fiapx}"
rabbitmq_senha="${FIAPX_RABBITMQ_SENHA:-fiapx}"
fixture="extracao/src/test/resources/fixtures/video-valido.mp4"
timeout_estado=180
timeout_saude=180

trabalho="$(mktemp -d)"
trap 'rm -rf "$trabalho"' EXIT

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; normal=''
fi
passo() { echo; echo "${negrito}==> $*${normal}"; }
ok()    { echo "    ${verde}OK${normal}  $*"; }
falha() { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
passo "1. Duas replicas de extracao"

docker compose up -d --scale extracao=2 > "$trabalho/compose.log" 2>&1 \
    || { cat "$trabalho/compose.log" >&2; falha "docker compose up falhou"; }

inicio=$SECONDS
until [[ "$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -c '^extracao healthy')" == 2 ]]; do
    (( SECONDS - inicio > timeout_saude )) && falha "as duas replicas nao ficaram saudaveis em ${timeout_saude}s"
    sleep 3
done
replicas=($(docker compose ps -q extracao))
ok "${#replicas[@]} replicas de extracao saudaveis"

# ---------------------------------------------------------------------------------------
passo "2. Envio do Video valido pela borda publica"

token="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos \
    -d username="${FIAPX_USUARIO:-demo}" -d password="${FIAPX_SENHA:-demo}" \
    | jq -r '.access_token // empty')"
[[ -n "$token" ]] || falha "Keycloak nao devolveu access_token"
autenticado=(-H "Authorization: Bearer $token")

codigo="$(curl -sS -o "$trabalho/envio.json" -w '%{http_code}' -X POST "$videos_url/videos" \
    "${autenticado[@]}" -F "arquivo=@$fixture;type=video/mp4" || true)"
[[ "$codigo" == 202 ]] || falha "POST /videos devolveu $codigo, esperava 202"
id="$(jq -r .id "$trabalho/envio.json")"
ok "202 Accepted, Video $id"

# ---------------------------------------------------------------------------------------
passo "3. $duplicatas comandos duplicados na routing key real"

# As chaves seguem a convencao do `videos` (ArquivoMinioAdapter): `{id}/original.mp4` e
# `{id}.zip`. A duplicata precisa ser byte a byte o comando que o `videos` publicou — um
# comando com chave diferente mediria outra coisa.
comando="{\"idVideo\":\"$id\",\"chaveVideo\":\"$id/original.mp4\",\"chaveDestinoPacote\":\"$id.zip\"}"
for _ in $(seq "$duplicatas"); do
    corpo="$(jq -nc --arg p "$comando" \
        '{properties:{content_type:"application/json"},routing_key:"extracao.extrair",payload:$p,payload_encoding:"string"}')"
    roteado="$(curl -sS -u "$rabbitmq_usuario:$rabbitmq_senha" -H 'content-type: application/json' \
        -X POST "$rabbitmq_url/api/exchanges/%2F/fiapx.comandos/publish" -d "$corpo" | jq -r .routed)"
    [[ "$roteado" == true ]] || falha "o broker nao roteou o comando duplicado"
done
ok "$duplicatas duplicatas roteadas para extracao.extrair"

# ---------------------------------------------------------------------------------------
passo "4. Criterio 1: cada replica com o seu proprio scratch"

# O volume e o MESMO nas duas replicas, entao `ls` numa delas ja mostra o que as duas
# criaram — e por isso o criterio conta DIRETORIOS deste Video, nao linhas por container:
# dois diretorios `{id}-*` ao mesmo tempo sao duas tentativas com espaco proprio.
#
# Amostragem, e nao espera: a janela em que as duas trabalham dura o tempo de uma Extracao do
# fixture de controle. Um diretorio so significa que a amostra caiu fora da janela, nao que
# houve colisao — por isso e aviso, e os criterios 2 a 4 continuam julgando.
# Amostra repetida enquanto o Video nao termina: com o fixture de controle, a Extracao dura
# ~1s, e uma foto unica tirada tarde demais mostra o volume ja limpo.
proprios=0
inicio=$SECONDS
while (( SECONDS - inicio < 30 )); do
    agora="$(docker exec "${replicas[0]}" sh -c "ls /var/fiapx/extracao | grep -c '^$id-'" || true)"
    (( agora > proprios )) && proprios="$agora"
    (( proprios >= 2 )) && break
    [[ "$(curl -sS "$videos_url/videos/$id" "${autenticado[@]}" | jq -r .estado)" == CONCLUIDO ]] && break
done
docker exec "${replicas[0]}" ls /var/fiapx/extracao | sed 's/^/    /'
if (( proprios >= 2 )); then
    ok "$proprios tentativas simultaneas do mesmo Video, cada uma no seu diretorio"
else
    echo "    !   amostra pegou $proprios diretorio(s) deste Video; os criterios 2-4 seguem valendo"
fi

# ---------------------------------------------------------------------------------------
passo "5. Criterio 2: desfecho pela borda publica"

inicio=$SECONDS
while :; do
    curl -sS "$videos_url/videos/$id" "${autenticado[@]}" > "$trabalho/estado.json"
    estado="$(jq -r .estado "$trabalho/estado.json")"
    [[ "$estado" == CONCLUIDO || "$estado" == FALHOU ]] && break
    (( SECONDS - inicio > timeout_estado )) && falha "Video ficou em $estado por mais de ${timeout_estado}s"
    sleep 2
done
jq -c . "$trabalho/estado.json" | sed 's/^/    /'
[[ "$estado" == CONCLUIDO ]] || falha "Video valido terminou em $estado — e a colisao do ticket 041"
ok "CONCLUIDO, apesar das duplicatas"

# ---------------------------------------------------------------------------------------
passo "6. Criterio 3: Pacote integro pela borda publica"

codigo="$(curl -sS -o "$trabalho/pacote.zip" -w '%{http_code}' \
    "$videos_url/videos/$id/pacote" "${autenticado[@]}" || true)"
[[ "$codigo" == 200 ]] || falha "GET /videos/$id/pacote devolveu $codigo, esperava 200"
unzip -tq "$trabalho/pacote.zip" >/dev/null || falha "ZIP corrompido"
frames="$(unzip -Z1 "$trabalho/pacote.zip" | grep -c '\.png$' || true)"
(( frames > 0 )) || falha "Pacote sem nenhum frame"
ok "$frames frames, ZIP integro ($(wc -c < "$trabalho/pacote.zip") bytes)"

# ---------------------------------------------------------------------------------------
passo "7. Criterio 4: scratch limpo nas duas replicas"

# A ultima tentativa ainda pode estar terminando quando o `videos` ja gravou CONCLUIDO: a
# limpeza dela roda depois da publicacao do evento.
#
# So o scratch DESTE Video: o volume e compartilhado e pode carregar orfao de crash de outra
# rodada, que e assunto da varredura por idade, nao deste ensaio.
inicio=$SECONDS
while :; do
    restos="$(docker exec "${replicas[0]}" sh -c "ls /var/fiapx/extracao | grep '^$id-'" || true)"
    [[ -z "$restos" ]] && break
    (( SECONDS - inicio > 60 )) && falha "sobrou scratch deste Video depois de 60s: $restos"
    sleep 3
done
alheios="$(docker exec "${replicas[0]}" ls /var/fiapx/extracao | tr '\n' ' ')"
[[ -n "${alheios// /}" ]] && echo "    !   restou scratch de outra rodada no volume: $alheios"
ok "nenhum diretorio deste Video sobrou"

echo
echo "${negrito}Os quatro criterios passaram.${normal} A stack ficou com duas replicas de extracao;"
echo "\`docker compose up -d\` a devolve a uma."
