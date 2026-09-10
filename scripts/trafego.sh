#!/usr/bin/env bash
# Trafego sintetico contra o Compose principal (ticket 093).
#
# RODE QUANDO quiser olhar os paineis com dados — o painel do ticket 092, o *RED Metrics* e o
# *JVM Overview* que o 091 fez funcionar. NUNCA para medir escala: medicao de escala e dos
# scripts de `scripts/carga/`, que rodam com `docker-compose.carga.yml` e portanto com a
# observabilidade desligada. Este script exige o oposto daquele overlay, e e por isso que ele
# mora aqui e nao la.
#
# E o unico script executavel deste repositorio que NAO REPROVA NADA. Ele produz sinal e relata;
# correcao sob carga e do `conservacao.sh`, fluxo ponta-a-ponta e do `smoke.sh`. A unica falha que
# ele se permite e a da infraestrutura que o tornaria inutil — sem stack de observabilidade ou com
# o SDK desligado ele geraria zero metrica E PARECERIA FUNCIONAR.
#
# O que ele faz, em 20 min (default):
#
#   blocos de 5 min ALTERNANDO sustentada (6 Video/min) e rajada (tudo de uma vez, o resto do
#   bloco drenando), comecando pela sustentada; mais dois cenarios rodando a corrida inteira — o
#   ciclo de quem esta OLHANDO (listagem filtrada e paginada, consulta, download do Pacote em 30%
#   dos CONCLUIDO) e um de erro a taxa fixa (415, 400, 404 de Video alheio, 409).
#
# Ele ACUMULA: sem `DELETE` no contrato HTTP, nada que ele cria pode ser apagado pela API, e
# inventar endpoint para script e proibido (AGENTS.md). O reset e `docker compose down -v`.
#
# Uso:
#   scripts/trafego.sh [duracao-em-minutos]     # default 20, multiplo de FIAPX_BLOCO_MIN
#
# Variaveis: FIAPX_BLOCO_MIN (5), FIAPX_TAXA_SUSTENTADA (6 Video/min), FIAPX_RAJADA_ENVIOS (60),
#            FIAPX_RAJADA_VUS (20), FIAPX_TAXA_CICLO (30/min), FIAPX_TAXA_ERRO (1/min),
#            FIAPX_FRACAO_DOWNLOAD (0.3), FIAPX_PESO_CONTROLE/CARGA/INVALIDO (85/10/5),
#            FIAPX_USUARIO/SENHA (demo), FIAPX_USUARIO_2/SENHA_2 (outro), FIAPX_AMOSTRA (10),
#            FIAPX_TETO_DRENAGEM (600 s), FIAPX_GRAFANA_URL, FIAPX_SEM_INHIBIT.
set -euo pipefail
export LC_ALL=C

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

duracao_min="${1:-${FIAPX_DURACAO_MIN:-20}}"
bloco_min="${FIAPX_BLOCO_MIN:-5}"
taxa_sustentada="${FIAPX_TAXA_SUSTENTADA:-6}"
rajada_envios="${FIAPX_RAJADA_ENVIOS:-60}"
rajada_vus="${FIAPX_RAJADA_VUS:-20}"
taxa_ciclo="${FIAPX_TAXA_CICLO:-30}"
taxa_erro="${FIAPX_TAXA_ERRO:-1}"
fracao_download="${FIAPX_FRACAO_DOWNLOAD:-0.3}"
peso_controle="${FIAPX_PESO_CONTROLE:-85}"
peso_carga="${FIAPX_PESO_CARGA:-10}"
peso_invalido="${FIAPX_PESO_INVALIDO:-5}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"
usuario_2="${FIAPX_USUARIO_2:-outro}"
senha_2="${FIAPX_SENHA_2:-outro}"
amostra="${FIAPX_AMOSTRA:-10}"
teto_drenagem="${FIAPX_TETO_DRENAGEM:-600}"
grafana_url="${FIAPX_GRAFANA_URL:-http://localhost:3000}"

# Vazao medida no ticket 026, registrada no `map.md`: 15,6 Video/min com 4 replicas e teto de
# 2 CPU. A demo sobe 2 replicas sem teto, entao este numero e referencia, nao promessa — o que
# ele serve para dizer e se a sustentada esta pedindo mais do que o sistema da.
capacidade_medida=15.6

rede="$(basename "$raiz")_default"
fixtures="$raiz/scripts/carga/fixtures"
saida="$raiz/scripts/saida/trafego-$(date +%Y%m%d-%H%M%S)"

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; amarelo=$'\e[33m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; amarelo=''; normal=''
fi
passo()  { echo; echo "${negrito}==> $*${normal}"; }
ok()     { echo "    ${verde}OK${normal}  $*"; }
aviso()  { echo "    ${amarelo}!${normal}   $*"; }
falha()  { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

[[ "$duracao_min" =~ ^[0-9]+$ && "$duracao_min" -ge 1 ]] || falha "duracao invalida: $duracao_min"

# Uma corrida de 20 min atravessa o tempo de suspensao deste host, e host suspenso no meio de uma
# corrida nao interrompe o script: ele volta, continua contando e o resultado fica corrompido em
# silencio. Por isso o script se re-executa sob `systemd-inhibit` em vez de pedir que voce lembre.
if [[ -z "${FIAPX_SEM_INHIBIT:-}" && -z "${FIAPX_INIBIDO:-}" ]] && command -v systemd-inhibit >/dev/null; then
    echo "==> re-executando sob systemd-inhibit (FIAPX_SEM_INHIBIT=1 desliga)"
    FIAPX_INIBIDO=1 exec systemd-inhibit \
        --what=idle:sleep:shutdown --who=trafego.sh --why="corrida de trafego de $duracao_min min" \
        "${BASH_SOURCE[0]}" "$duracao_min"
fi

# ---------------------------------------------------------------------------------------
passo "0. Ferramentas e fixtures"

for ferramenta in docker curl jq shuf awk; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done

scripts/carga/gera-fixtures.sh
# O terceiro fixture e escrito aqui, e nao no `gera-fixtures.sh`: aquele script e de
# `scripts/carga/` e este ticket nao toca em nada de la. O precedente e o proprio
# `conservacao.sh`, que escreve este mesmo arquivo no modo `mata-publicacao` — extensao e
# content-type passam pela borda, e quem reprova e o ffprobe do `extracao`. E dele que sai o
# `resultado=falhou` da metrica.
mkdir -p "$fixtures"
echo "isto nao e um video" > "$fixtures/invalido.mp4"
for fixture in controle-3s.mp4 carga-2min.mp4 invalido.mp4; do
    [[ -f "$fixtures/$fixture" ]] || falha "fixture $fixture nao existe"
done
mkdir -p "$saida"
ok "tres fixtures em scripts/carga/fixtures/, saida em ${saida#$raiz/}"

# ---------------------------------------------------------------------------------------
# As duas guardas, nesta ordem de proposito: a primeira diz POR QUE voce esta cego, com mensagem
# acionavel; a segunda pega o caso que a primeira nao ve (coletor de pe, exportador quebrado). A
# segunda sozinha acusaria "sem serie" numa stack recem-subida, que e legitimo.
passo "1. A observabilidade esta de pe e os servicos exportam"

estado="$(docker compose ps --format '{{.Service}} {{.State}} {{.Health}}' 2>/dev/null || true)"
[[ -n "$estado" ]] || falha "nenhum servico do Compose de pe — rode 'docker compose up -d' primeiro"

for servico in videos extracao notificacao observabilidade; do
    grep -q "^$servico running" <<< "$estado" \
        || falha "servico '$servico' nao esta running:"$'\n'"$estado"
done
ok "videos, extracao, notificacao e observabilidade de pe"

for id in $(docker compose ps -q videos extracao notificacao); do
    if docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$id" \
        | grep -qi '^QUARKUS_OTEL_SDK_DISABLED=true$'; then
        nome="$(docker inspect -f '{{.Name}}' "$id")"
        falha "$nome subiu com QUARKUS_OTEL_SDK_DISABLED=true — voce subiu com o overlay de carga"$'\n'\
"           (docker-compose.carga.yml desliga o SDK dos tres servicos e zera a stack de"$'\n'\
"            observabilidade: a corrida geraria zero metrica e pareceria funcionar)."$'\n'\
"            Suba so com 'docker compose up -d' e rode de novo."
    fi
done
ok "nenhum dos tres com o SDK desligado"

# `count(group by (job) (...))`: a pergunta e quantos dos nossos servicos tem ALGUMA serie, e nao
# qual metrica — nomear uma metrica aqui criaria uma segunda verdade sobre o que o painel cobra,
# que e trabalho do passo 12 do `smoke.sh`. O laco existe porque o exportador tem intervalo: num
# `up` recem-feito as series demoram a aparecer, e isso nao e defeito.
consulta_prometheus() {
    curl -sS -G "$grafana_url/api/datasources/proxy/uid/prometheus/api/v1/query" \
        --data-urlencode "query=$1" 2>/dev/null | jq -r '.data.result[0].value[1] // empty'
}
inicio=$SECONDS
while :; do
    exportando="$(consulta_prometheus 'count(group by (job) ({job=~"fiapx-.+"}))' || true)"
    [[ "${exportando:-0}" == 3 ]] && break
    (( SECONDS - inicio > 120 )) && falha \
        "so $((${exportando:-0})) de 3 servicos com serie no Prometheus apos 2 min."$'\n'\
"           A stack esta de pe e o SDK ligado, entao o que falta e o caminho entre eles:"$'\n'\
"           confira o endpoint OTLP dos servicos e o coletor do container observabilidade."
    sleep 5
done
ok "os tres servicos com serie no Prometheus"

# ---------------------------------------------------------------------------------------
passo "2. O que esta sendo pedido"

blocos=$(( duracao_min / bloco_min ))
(( blocos >= 1 )) || falha "duracao de $duracao_min min e menor que um bloco de $bloco_min min"
(( duracao_min % bloco_min == 0 )) \
    || aviso "duracao nao e multiplo do bloco: o ultimo bloco fica incompleto"
sustentados=$(( (blocos + 1) / 2 ))
rajadas=$(( blocos / 2 ))

cat <<RESUMO
    duracao .............: $duracao_min min em $blocos bloco(s) de $bloco_min min
                           $sustentados sustentada(s) + $rajadas rajada(s), comecando pela sustentada
    sustentada ..........: $taxa_sustentada Video/min   (capacidade medida no ticket 026: $capacidade_medida Video/min)
    rajada ..............: $rajada_envios envios em t=0, $rajada_vus VUs, drenando pelo resto do bloco
    ciclo de vida .......: $taxa_ciclo iteracoes/min, download em $fracao_download dos CONCLUIDO
    erro ................: $taxa_erro iteracao/min x 4 classes (415, 400, 404, 409)
    mistura .............: $peso_controle% controle-3s / $peso_carga% carga-2min / $peso_invalido% invalido
    donos ...............: $usuario e $usuario_2
RESUMO

# A conta e so da sustentada: a rajada pede de propósito mais do que o sistema da, e e a
# drenagem dela que os paineis de fila existem para mostrar.
awk -v taxa="$taxa_sustentada" -v cap="$capacidade_medida" 'BEGIN {
    if (taxa >= cap)
        printf "    !   a sustentada pede %.1f Video/min contra capacidade medida de %.1f: o backlog\n        atravessa todos os blocos e os dois tipos param de se distinguir no painel\n", taxa, cap;
    else
        printf "    OK  a sustentada fica em %.0f%% da capacidade medida; so a rajada cria fila\n", 100 * taxa / cap;
}'

# ---------------------------------------------------------------------------------------
passo "3. Trafego"

echo "    k6 em $(date +%H:%M:%S), termina por volta de $(date -d "+$duracao_min minutes" +%H:%M:%S)"
docker run --rm --network "$rede" --user "$(id -u):$(id -g)" \
    -v "$raiz/scripts/trafego.js:/trafego.js:ro" \
    -v "$fixtures:/fixtures:ro" \
    -v "$saida:/saida" \
    -e VIDEOS_URL=http://videos:8080 \
    -e KEYCLOAK_URL=http://keycloak:8080 \
    -e USUARIO="$usuario" -e SENHA="$senha" \
    -e USUARIO_2="$usuario_2" -e SENHA_2="$senha_2" \
    -e DURACAO_MIN="$duracao_min" -e BLOCO_MIN="$bloco_min" \
    -e TAXA_SUSTENTADA="$taxa_sustentada" \
    -e RAJADA_ENVIOS="$rajada_envios" -e RAJADA_VUS="$rajada_vus" \
    -e TAXA_CICLO="$taxa_ciclo" -e TAXA_ERRO="$taxa_erro" \
    -e FRACAO_DOWNLOAD="$fracao_download" \
    -e PESO_CONTROLE="$peso_controle" -e PESO_CARGA="$peso_carga" -e PESO_INVALIDO="$peso_invalido" \
    grafana/k6:latest run --quiet --console-output=/saida/trafego.log /trafego.js \
    | tee "$saida/k6.out" \
    || aviso "k6 encerrou com codigo $? — o que ele enviou antes disso continua valendo"

# Duas listas, e a diferenca entre elas foi medida na corrida de validacao de 10 min: o censo
# pergunta ao Postgres e nao se importa com dono, mas a amostra consulta pela API COMO `demo` — os
# Videos enviados como `outro` (30% do trafego) voltavam 404 e apareciam como DIVERGENCIA sendo
# comportamento correto do contrato. Por isso a linha `ACEITO` carrega o dono.
#
# `\b` e nao `$`: o k6 escreve `msg="ACEITO <id> demo"`, entao a linha termina em aspas e um
# ancora de fim de linha nao casa nada — a primeira versao deste filtro produziu lista vazia e a
# amostra da corrida de 20 min passou em silencio, imprimindo so o total.
sed -n "s/.*ACEITO \([0-9a-f-]*\).*/\1/p" "$saida/trafego.log" | sort -u > "$saida/aceitos.txt"
sed -n "s/.*ACEITO \([0-9a-f-]*\) $usuario\b.*/\1/p" "$saida/trafego.log" | sort -u > "$saida/aceitos-$usuario.txt"
aceitos="$(wc -l < "$saida/aceitos.txt")"
recusados="$(grep -c 'RECUSADO' "$saida/trafego.log" || true)"
inesperados="$(grep -c 'INESPERADO' "$saida/trafego.log" || true)"
corridas="$(grep -c 'CORRIDA' "$saida/trafego.log" || true)"
ok "$aceitos Video(s) aceito(s), $recusados recusado(s), $inesperados resposta(s) inesperada(s)"
(( inesperados == 0 )) || aviso "veja as linhas INESPERADO em ${saida#$raiz/}/trafego.log"
(( corridas == 0 )) || echo "    $corridas corrida(s) do 409: o Video concluiu antes do pedido do Pacote, e nao e defeito"
(( aceitos > 0 )) || falha "nenhum envio aceito — nao ha o que drenar nem o que olhar no painel"

# ---------------------------------------------------------------------------------------
# Censo e amostra pelo `oraculo.sh` intocado: ele ja e a verdade sobre "o que o sistema fez com
# esta lista", inclusive o estado AUSENTE (aceito com 202 e sem linha no banco).
passo "4. Drenagem (teto de $teto_drenagem s)"

inicio=$SECONDS
while :; do
    censo="$(scripts/carga/oraculo.sh censo "$saida/aceitos.txt")"
    terminais="$(awk '$1=="CONCLUIDO"||$1=="FALHOU"{s+=$2} END{print s+0}' <<< "$censo")"
    (( terminais >= aceitos )) && { ok "drenou: $terminais de $aceitos em $((SECONDS - inicio)) s"; break; }
    if (( SECONDS - inicio > teto_drenagem )); then
        aviso "teto estourado com $terminais de $aceitos em estado terminal — relatando o que sobrou"
        break
    fi
    sleep 10
done
echo "$censo" | tee "$saida/censo.txt" | sed 's/^/    /'

passo "5. Amostra pela API, como $usuario"
scripts/carga/oraculo.sh amostra "$saida/aceitos-$usuario.txt" "$amostra" | tee "$saida/amostra.txt" \
    || aviso "a amostra achou divergencia — este script relata, nao reprova (ver ticket 093)"

# ---------------------------------------------------------------------------------------
passo "Fim"
echo "    saida ....: ${saida#$raiz/}"
echo "    painel ...: $grafana_url — e a home, cai nele sem navegar (ticket 092)"
echo "    HTTP/JVM .: 'RED Metrics (classic)' e 'JVM Overview' no mesmo Grafana (ticket 091)"
echo "    reset ....: docker compose down -v (nao ha DELETE no contrato HTTP)"
