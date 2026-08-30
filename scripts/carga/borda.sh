#!/usr/bin/env bash
# Escala da borda (ticket 028).
#
# A celula sob julgamento e a do `videos` na tabela "O que escala, e como" de
# docs/arquitetura.md: hoje "Nunca medido". Duas perguntas, deliberadamente separadas —
# ver o ticket para o porque do corte:
#
#   escala        N replicas atras do proxy aguentam mais envios simultaneos que uma?
#                 Relata, nao reprova: a vazao/latencia entram na tabela, o portao e so
#                 sobre a corrida ter sido valida (nada preso, nada FALHOU, frames certos).
#   mata-replica  Com N>1, matar uma replica DURANTE a rajada deveria custar ZERO
#                 requisicao — as outras continuam aceitando. Ao contrario do
#                 `mata-videos` do conservacao.sh (replica unica), aqui o criterio 1 (zero
#                 nao-202) NAO e dispensado: e o que este modo existe para provar.
#
# Reusa gera-fixtures.sh, injetor.js e oraculo.sh sem tocar neles — o que muda e o alvo do
# injetor (o proxy, nao o `videos` direto) e os portoes. `docker-compose.carga.yml` ganhou
# `videos-proxy` (nginx) e `videos: deploy.replicas` para isto; ver o comentario la para o
# porque de nao dar para publicar a porta 8080 de N containers ao mesmo tempo.
#
# Uso:
#   scripts/carga/borda.sh escala <N> [envios]
#   scripts/carga/borda.sh mata-replica <N> [envios]
#
# Variaveis: FIAPX_VUS, FIAPX_EXTRACAO_REPLICAS (default 2), FIAPX_EXTRACAO_CPUS (default 1;
#            teto baixo de proposito — esta maquina tem menos nucleos que a do 026/027, e o
#            que esta sob julgamento aqui e o `videos`, nao o `extracao`), FIAPX_FIXTURE,
#            FIAPX_AMOSTRA, FIAPX_ATRASO_KILL.
set -euo pipefail
export LC_ALL=C

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

modo="${1:?uso: borda.sh escala|mata-replica <N> [envios]}"
n_videos="${2:?informe N de replicas do videos}"
envios="${3:-${FIAPX_ENVIOS:-400}}"
vus="${FIAPX_VUS:-$envios}"
extracao_replicas="${FIAPX_EXTRACAO_REPLICAS:-2}"
extracao_cpus="${FIAPX_EXTRACAO_CPUS:-1}"
fixture="${FIAPX_FIXTURE:-controle-3s.mp4}"
amostra="${FIAPX_AMOSTRA:-10}"
frames_esperados="${FIAPX_FRAMES_ESPERADOS:-3}"   # controle-3s.mp4 a 1 fps
atraso_kill="${FIAPX_ATRASO_KILL:-2}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"
segundos_por_video=1

case "$modo" in escala|mata-replica) ;; *) echo "modo desconhecido: $modo" >&2; exit 2 ;; esac
[[ "$n_videos" =~ ^[0-9]+$ && "$n_videos" -ge 1 ]] || { echo "N invalido: $n_videos" >&2; exit 2; }
if [[ "$modo" == mata-replica && "$n_videos" -lt 2 ]]; then
    echo "mata-replica exige N >= 2 — matar a unica replica nao testaria sobrevivencia, testaria o 025 de novo" >&2
    exit 2
fi

# Docker-outside-of-Docker: nesta sessao o dockerd real roda no host, fora do container onde
# este script executa, e os dois so concordam no CONTEUDO de `/workspace` — nao no CAMINHO.
# Bind mount por caminho relativo (`./docker/...`) resolve do lado do daemon, que nao tem
# `/workspace`; precisa do caminho real do host, que o devcontainer expoe em
# LOCAL_WORKSPACE_FOLDER. Onde essa variavel nao existe (devcontainer local, sem DooD),
# raiz_docker cai em $raiz e o comportamento e o de sempre.
raiz_docker="${LOCAL_WORKSPACE_FOLDER:-$raiz}"

export COMPOSE_PROJECT_NAME="$(basename "$raiz")"
export FIAPX_EXTRACAO_REPLICAS="$extracao_replicas"
export FIAPX_EXTRACAO_CPUS="$extracao_cpus"
export FIAPX_VIDEOS_REPLICAS="$n_videos"
compose=(docker compose --project-directory "$raiz_docker" -f docker-compose.yml -f docker-compose.carga.yml)
rotulo="${FIAPX_ROTULO:-${modo}-n${n_videos}}"
saida="$raiz/scripts/carga/saida/$rotulo"
rede="${COMPOSE_PROJECT_NAME}_default"
psql_() { "${compose[@]}" exec -T postgres psql -U fiapx -d fiapx_videos -q -t -A -F' ' -v ON_ERROR_STOP=1; }

# `oraculo.sh amostra` fala com `localhost:8080`/`8081` — certo quando quem roda o harness e o
# mesmo host do dockerd. Nesta sessao nao e: e Docker-outside-of-Docker (mesmo motivo do
# raiz_docker acima), e o container onde este script roda nao tem rota nenhuma ate a porta
# publicada no host real, so ate a API do Docker. Por isso a amostra aqui roda DENTRO de um
# container na rede do Compose, batendo em `videos-proxy:8080` e `keycloak:8080` direto — o
# mesmo truque do injetor, so que para o oraculo. Onde dockerd e o host coincidem isto tambem
# funciona (a rede do Compose sempre existe), so e mais passo do que precisaria.
amostra_via_rede() {
    local ids="$1" quantidade="$2"
    cat > "$saida/amostra.sh" <<'AMOSTRA'
set -eu
apk add --no-cache curl jq coreutils >/dev/null 2>&1
token=$(curl -sS -X POST "$KEYCLOAK_URL/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos -d "username=$USUARIO" -d "password=$SENHA" \
    | jq -r '.access_token // empty')
[ -n "$token" ] || { echo "Keycloak nao devolveu token para $USUARIO" >&2; exit 1; }
divergencias=0
for id in $(shuf -n "$QUANTIDADE" /dados/ids.txt); do
    http=$(curl -sS -o /tmp/r.json -w '%{http_code}' "$VIDEOS_URL/videos/$id" -H "Authorization: Bearer $token")
    estado=$(jq -r '.estado // "-"' /tmp/r.json)
    if [ "$http" != 200 ] || { [ "$estado" != CONCLUIDO ] && [ "$estado" != FALHOU ]; }; then
        echo "    DIVERGENCIA  $id  HTTP $http  estado $estado"
        divergencias=$((divergencias + 1))
    else
        echo "    ok           $id  $estado"
    fi
done
total=$(curl -sS "$VIDEOS_URL/videos?tamanho=1" -H "Authorization: Bearer $token" | jq -r '.total')
echo "    GET /videos informa total=$total para $USUARIO"
exit $(( divergencias > 0 ? 1 : 0 ))
AMOSTRA
    docker run --rm --network "$rede" \
        -v "$raiz_docker/scripts/carga/saida/$rotulo/amostra.sh:/amostra.sh:ro" \
        -v "$raiz_docker/scripts/carga/saida/$rotulo/$(basename "$ids"):/dados/ids.txt:ro" \
        -e VIDEOS_URL=http://videos-proxy:8080 -e KEYCLOAK_URL=http://keycloak:8080 \
        -e USUARIO="$usuario" -e SENHA="$senha" -e QUANTIDADE="$quantidade" \
        --entrypoint sh alpine:3.20 /amostra.sh
}

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; amarelo=$'\e[33m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; amarelo=''; normal=''
fi
passo()  { echo; echo "${negrito}==> $*${normal}"; }
ok()     { echo "    ${verde}OK${normal}  $*"; }
aviso()  { echo "    ${amarelo}!${normal}   $*"; }
falha()  { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
passo "0. Dependencias, fixtures e stack limpa"

for ferramenta in docker curl jq shuf; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done
scripts/carga/gera-fixtures.sh
[[ -f "scripts/carga/fixtures/$fixture" ]] || falha "fixture $fixture nao existe"
rm -rf "$saida"; mkdir -p "$saida"

# down -v entre corridas, na mesma razao do 026 (item 4 do metodo la): comparar N=1 com N=3
# sobre banco e bucket sujos da corrida anterior compararia duas maquinas diferentes, nao dois
# N diferentes.
"${compose[@]}" down -v --remove-orphans > "$saida/down.log" 2>&1 || true
ok "fixture $fixture, saida em scripts/carga/saida/$rotulo/, stack derrubada antes de subir"

# ---------------------------------------------------------------------------------------
passo "1. Stack de pe com $n_videos replica(s) de videos atras do proxy, $extracao_replicas de extracao"

"${compose[@]}" up -d > "$saida/compose.log" 2>&1 \
    || { cat "$saida/compose.log" >&2; falha "compose up falhou"; }

esperados=$(( n_videos + extracao_replicas + 2 ))  # + notificacao + videos-proxy
inicio=$SECONDS
while :; do
    estado_saude="$("${compose[@]}" ps --format '{{.Service}} {{.Health}}' \
        | grep -E '^(videos|videos-proxy|extracao|notificacao) ' || true)"
    saudaveis="$(grep -c ' healthy$' <<< "$estado_saude" || true)"
    [[ "$saudaveis" == "$esperados" ]] && break
    (( SECONDS - inicio > 240 )) && falha "servicos nao ficaram saudaveis:"$'\n'"$estado_saude"
    sleep 3
done
ok "$esperados containers saudaveis ($n_videos de videos atras do proxy, $extracao_replicas de extracao)"

# ---------------------------------------------------------------------------------------
passo "2. Criterio, fixado antes de rodar"

drenagem_esperada=$(( envios * segundos_por_video / extracao_replicas ))
limite_drenagem=$(( drenagem_esperada * 3 ))
(( limite_drenagem < 180 )) && limite_drenagem=180

echo "    modo ............: $modo"
echo "    replicas videos .: $n_videos (atras do proxy, porta 8080 publicada so por ele)"
echo "    rajada ..........: $envios envios de $fixture, $vus conexoes simultaneas"
echo "    limite de drenagem: ${limite_drenagem}s"
echo
if [[ "$modo" == mata-replica ]]; then
    echo "    0. Ao menos um 202 antes da queda — sem isso a rodada nao abriu janela nenhuma."
    echo "    1. ZERO respostas nao-202 na rajada inteira, MESMO com uma replica morta no meio."
    echo "       Este e o criterio central: com N>1, matar uma replica deveria custar zero"
    echo "       requisicao porque as outras continuam aceitando — ao contrario do"
    echo "       mata-videos do conservacao.sh (replica unica), aqui ele NAO e dispensado."
else
    echo "    1. Zero respostas nao-202 entre os envios da rajada (sem falha injetada aqui)."
fi
echo "    2. 100% dos ids aceitos em estado terminal (CONCLUIDO ou FALHOU) em ${limite_drenagem}s."
echo "    3. Zero presos em RECEBIDO ou PROCESSANDO ao fim."
echo "    4. Amostra de $amostra ids conferida pela API, como dono: 200 e estado terminal."
echo "    5. Zero FALHOU. Fixture e h264 valido — todo FALHOU e um Video bom declarado ruim."
echo "    6. quantidade_frames == $frames_esperados para todo CONCLUIDO (portao herdado do 026:"
echo "       terminal 'certo' nao basta, tem que ser terminal com o conteudo certo)."

# ---------------------------------------------------------------------------------------
passo "3. Rajada, contra o proxy (nao contra o videos direto)"

docker run --rm --network "$rede" --user "$(id -u):$(id -g)" \
    -v "$raiz_docker/scripts/carga/injetor.js:/injetor.js:ro" \
    -v "$raiz_docker/scripts/carga/fixtures:/fixtures:ro" \
    -v "$raiz_docker/scripts/carga/saida/$rotulo:/saida" \
    -e VIDEOS_URL=http://videos-proxy:8080 \
    -e KEYCLOAK_URL=http://keycloak:8080 \
    -e USUARIO="$usuario" -e SENHA="$senha" \
    -e ARQUIVO="/fixtures/$fixture" \
    -e VUS="$vus" -e ENVIOS="$envios" \
    grafana/k6:latest run --quiet --console-output=/saida/injetor.log /injetor.js \
    > "$saida/k6.out" 2> "$saida/k6.err" &
k6_pid=$!

if [[ "$modo" == mata-replica ]]; then
    sleep "$atraso_kill"
    alvo="$("${compose[@]}" ps -q videos | head -1)"
    docker kill "$alvo" >/dev/null
    aviso "uma replica de videos morta a ${atraso_kill}s do inicio da rajada (container ${alvo:0:12}, de $n_videos)"
fi

wait "$k6_pid" && k6_codigo=0 || k6_codigo=$?
cat "$saida/k6.out"
(( k6_codigo == 0 )) || aviso "k6 encerrou com codigo $k6_codigo"

aceitos_arquivo="$saida/aceitos.txt"
sed -n 's/.*ACEITO \([0-9a-f-]*\).*/\1/p' "$saida/injetor.log" | sort -u > "$aceitos_arquivo"
aceitos="$(wc -l < "$aceitos_arquivo")"
recusados="$(grep -c 'RECUSADO' "$saida/injetor.log" || true)"

echo "    aceitos (202) ...: $aceitos"
echo "    recusados .......: $recusados"
if (( recusados > 0 )); then
    grep -o 'RECUSADO.*' "$saida/injetor.log" | sort | uniq -c | sort -rn | sed 's/^/        /'
fi

if [[ "$modo" == mata-replica ]]; then
    "${compose[@]}" up -d videos > /dev/null 2>&1
    aviso "replica devolvida — nao e o que este modo julga, e so higiene para a proxima corrida"
fi

# ---------------------------------------------------------------------------------------
passo "4. Drenagem"

if (( aceitos == 0 )); then
    echo "    ${vermelho}INVALIDA${normal}  0. Nenhum 202 antes/durante a rajada: nenhuma janela foi aberta."
    echo "${negrito}${vermelho}Rodada invalida${normal}: aumente FIAPX_ATRASO_KILL (atual ${atraso_kill}s) e repita."
    echo "    Saida completa: scripts/carga/saida/$rotulo/"
    exit 2
fi

inicio_drenagem=$SECONDS
while :; do
    censo="$(scripts/carga/oraculo.sh censo "$aceitos_arquivo")"
    terminais="$(awk '$1=="CONCLUIDO"||$1=="FALHOU"{s+=$2} END{print s+0}' <<< "$censo")"
    decorrido=$(( SECONDS - inicio_drenagem ))
    printf '    %4ds  %s\n' "$decorrido" "$(tr '\n' ' ' <<< "$censo")"
    (( terminais == aceitos )) && break
    (( decorrido > limite_drenagem )) && { aviso "limite de ${limite_drenagem}s estourado"; break; }
    sleep 5
done
drenagem=$(( SECONDS - inicio_drenagem ))

# ---------------------------------------------------------------------------------------
passo "5. Censo final, amostra pela API e frames"

censo="$(scripts/carga/oraculo.sh censo "$aceitos_arquivo")"
sed 's/^/    /' <<< "$censo"
tee "$saida/censo.txt" >/dev/null <<< "$censo"

conta() { awk -v e="$1" '$1==e{print $2}' <<< "$censo"; }
concluidos="$(conta CONCLUIDO)"; falhados="$(conta FALHOU)"
ausentes="$(conta AUSENTE)"; recebidos="$(conta RECEBIDO)"; processando="$(conta PROCESSANDO)"
: "${concluidos:=0}" "${falhados:=0}" "${ausentes:=0}" "${recebidos:=0}" "${processando:=0}"

if (( aceitos > 0 )); then
    amostra_via_rede "$aceitos_arquivo" "$(( amostra < aceitos ? amostra : aceitos ))" \
        && amostra_ok=true || amostra_ok=false
else
    amostra_ok=true
fi

frames_errados=0
if (( concluidos > 0 )); then
    frames_errados="$( { echo "CREATE TEMP TABLE enviados (id uuid PRIMARY KEY);"
                          echo "COPY enviados FROM STDIN;"; cat "$aceitos_arquivo"; echo '\.'
                          echo "SELECT count(*) FROM enviados e JOIN video v ON v.id = e.id
                                 WHERE v.estado = 'CONCLUIDO' AND v.quantidade_frames IS DISTINCT FROM $frames_esperados;"
                        } | psql_ )"
fi
echo "    frames errados ..: $frames_errados (esperado $frames_esperados por Video concluido)"

# ---------------------------------------------------------------------------------------
passo "6. Veredito"

reprovacoes=0
julga() {
    local nome="$1" condicao="$2" detalhe="$3"
    if [[ "$condicao" == true ]]; then ok "$nome — $detalhe"
    else echo "    ${vermelho}REPROVA${normal}  $nome — $detalhe"; reprovacoes=$((reprovacoes + 1)); fi
}

if [[ "$modo" == mata-replica ]]; then
    ok "0. Rodada valida — $aceitos aceito(s)"
fi
julga "1. Zero nao-202" "$([[ $recusados == 0 ]] && echo true || echo false)" \
      "$recusados recusas em $envios envios"
julga "2. Todos terminais" "$([[ $(( concluidos + falhados )) == "$aceitos" ]] && echo true || echo false)" \
      "$(( concluidos + falhados ))/$aceitos em ${drenagem}s (limite ${limite_drenagem}s)"
julga "3. Zero presos" "$([[ $(( recebidos + processando + ausentes )) == 0 ]] && echo true || echo false)" \
      "$recebidos RECEBIDO, $processando PROCESSANDO, $ausentes AUSENTE"
julga "4. Amostra pela API" "$amostra_ok" "$amostra ids conferidos como dono"
julga "5. Zero FALHOU" "$([[ $falhados == 0 ]] && echo true || echo false)" \
      "$falhados Video(s) validos declarados FALHOU"
julga "6. Frames certos" "$([[ $frames_errados == 0 ]] && echo true || echo false)" \
      "$frames_errados de $concluidos concluidos com quantidade_frames != $frames_esperados"

echo
if (( reprovacoes == 0 )); then
    echo "${negrito}${verde}$modo preservado${normal} com N=$n_videos: $aceitos aceitos, $aceitos com desfecho."
else
    echo "${negrito}${vermelho}$reprovacoes criterio(s) reprovado(s)${normal} no modo $modo, N=$n_videos."
fi
echo "    Saida completa: scripts/carga/saida/$rotulo/"

# ---------------------------------------------------------------------------------------
passo "7. Latencia do 202 e drenagem (para a tabela)"
grep -B1 -A4 'latencia do 202' "$saida/k6.out" || true
awk -v c="$concluidos" -v d="$drenagem" 'BEGIN{ if (d>0) printf "    vazao aproximada : %.4f Video/s (%d concluidos em %ds)\n", c/d, c, d }'

exit $(( reprovacoes > 0 ? 1 : 0 ))
