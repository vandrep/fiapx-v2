#!/usr/bin/env bash
# Experimento de conservacao sob pico (ticket 025).
#
# A afirmacao sob julgamento e a que o enunciado cobra direto: *"em caso de picos, o sistema
# nao deve perder uma requisicao"*. Conservacao nao e vazao — e um invariante de correcao, e
# um teste de carga so serve para *falsea-lo*. Por isso aqui nao se otimiza nada: mede-se se
# algum envio ficou sem desfecho.
#
# Tres modos, cada um com o seu criterio impresso ANTES de rodar. Sem limiar declarado antes,
# todo resultado vira narrativa pos-fato:
#
#   limpo          rajada sem falha injetada. A fila absorve — todo mundo sabe —, e e por
#                  isso que este modo sozinho prova pouco. Ele e a linha de base.
#   mata-extracao  `docker kill` numa replica do extracao durante a drenagem. Exercita ack
#                  manual, requeue e x-delivery-limit: "o worker morre no meio", que a doc
#                  afirma e nada verificava.
#   mata-videos    `docker kill videos` durante a rajada. E o unico modo que exercita a
#                  varredura de reconciliacao e as colunas marcadoras do ADR 0003 — aquele
#                  ADR existe inteiro para fechar a janela entre gravar e publicar, e essa
#                  janela nunca tinha sido aberta de verdade.
#
# Uso:
#   scripts/carga/conservacao.sh [limpo|mata-extracao|mata-videos] [envios]
#
# Variaveis: FIAPX_VUS (default = envios, rajada instantanea), FIAPX_EXTRACAO_REPLICAS,
#            FIAPX_FIXTURE, FIAPX_AMOSTRA.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

modo="${1:-limpo}"
envios="${2:-${FIAPX_ENVIOS:-400}}"
vus="${FIAPX_VUS:-$envios}"
replicas="${FIAPX_EXTRACAO_REPLICAS:-4}"
fixture="${FIAPX_FIXTURE:-controle-3s.mp4}"
amostra="${FIAPX_AMOSTRA:-10}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"

# Tempo de servico de um Video do fixture de controle numa replica, medido no proprio
# harness: 400 envios drenaram em 98 s com 4 replicas. E o numero que da o "tempo esperado
# de drenagem" do criterio 2 — e ele precisa ser o medido, nao um chute generoso: um limite
# folgado demais transforma o criterio 2 em "eventualmente termina", que nada reprova.
segundos_por_video=1

export FIAPX_EXTRACAO_REPLICAS="$replicas"
compose=(docker compose -f docker-compose.yml -f docker-compose.carga.yml)
# Uma pasta por rodada, e nao uma so: os tres modos do 025 e as quatro contagens de replica
# do 026 sao experimentos distintos, e sobrescrever a saida do anterior apagaria a linha de
# base contra a qual o proximo e lido.
rotulo="${FIAPX_ROTULO:-$modo}"
saida="$raiz/scripts/carga/saida/$rotulo"
rede="$(basename "$raiz")_default"

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; amarelo=$'\e[33m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; amarelo=''; normal=''
fi
passo()  { echo; echo "${negrito}==> $*${normal}"; }
ok()     { echo "    ${verde}OK${normal}  $*"; }
aviso()  { echo "    ${amarelo}!${normal}   $*"; }
falha()  { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

case "$modo" in limpo|mata-extracao|mata-videos) ;; *) falha "modo desconhecido: $modo" ;; esac

# ---------------------------------------------------------------------------------------
passo "0. Dependencias e fixtures"

for ferramenta in docker curl jq shuf; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done
scripts/carga/gera-fixtures.sh
[[ -f "scripts/carga/fixtures/$fixture" ]] || falha "fixture $fixture nao existe"
rm -rf "$saida"; mkdir -p "$saida"
ok "fixture $fixture, saida em scripts/carga/saida/$rotulo/"

# ---------------------------------------------------------------------------------------
passo "1. Stack de pe com $replicas replicas do extracao"

"${compose[@]}" up -d > "$saida/compose.log" 2>&1 \
    || { cat "$saida/compose.log" >&2; falha "compose up falhou"; }

# Mesma razao do smoke.sh para nao usar `up --wait`: o minio-seed e one-shot e sai com 0, o
# que o --wait le como servico morto. Com replicas, o extracao aparece N vezes no ps.
esperados=$(( 2 + replicas ))
inicio=$SECONDS
while :; do
    estado_saude="$("${compose[@]}" ps --format '{{.Service}} {{.Health}}' \
        | grep -E '^(videos|extracao|notificacao) ' || true)"
    saudaveis="$(grep -c ' healthy$' <<< "$estado_saude" || true)"
    [[ "$saudaveis" == "$esperados" ]] && break
    (( SECONDS - inicio > 240 )) && falha "servicos nao ficaram saudaveis:"$'\n'"$estado_saude"
    sleep 3
done
ok "$esperados containers saudaveis ($replicas de extracao, teto de ${FIAPX_EXTRACAO_CPUS:-2} CPU cada)"

# ---------------------------------------------------------------------------------------
passo "2. Criterio, fixado antes de rodar"

drenagem_esperada=$(( envios * segundos_por_video / replicas ))
limite_drenagem=$(( drenagem_esperada * 3 ))
(( limite_drenagem < 120 )) && limite_drenagem=120

echo "    modo ............: $modo"
echo "    rajada ..........: $envios envios de $fixture, $vus conexoes simultaneas"
echo "    drenagem esperada: ${drenagem_esperada}s  (limite: ${limite_drenagem}s = 3x)"
if [[ "$modo" == mata-videos ]]; then
    # A folga contra crash do ReconciliarPublicacoesPendentesUseCase e de 1 min, e o
    # @Scheduled roda a cada 30 s: um comando nao publicado so pode ser reenviado depois
    # disso. Cobrar o limite normal aqui seria cobrar do sistema uma garantia que ele nao
    # promete.
    limite_drenagem=$(( limite_drenagem + 150 ))
    echo "    + reconciliacao .: +150s (folga de 1 min do ADR 0003 + intervalo de 30s)"
    echo "                       limite efetivo: ${limite_drenagem}s"
fi
echo
echo "    1. Zero respostas nao-202 entre os envios da rajada."
if [[ "$modo" == mata-videos ]]; then
    echo "       ${amarelo}DISPENSADO neste modo${normal}: a borda e derrubada de proposito no meio da"
    echo "       rajada, entao conexao recusada e o efeito procurado, nao o defeito. O que"
    echo "       se julga aqui e o destino do que foi aceito ANTES da queda."
else
    echo "       Qualquer 5xx, recusa de conexao ou timeout conta como perda."
fi
echo "    2. 100% dos ids aceitos em estado terminal (CONCLUIDO ou FALHOU) em ${limite_drenagem}s."
echo "    3. Zero presos em RECEBIDO ou PROCESSANDO ao fim."
echo "    4. Amostra de $amostra ids conferida pela API, como dono: 200 e estado terminal."
echo "    5. Zero FALHOU. O fixture e um h264 valido de $fixture — todo FALHOU aqui e um"
echo "       Video bom declarado ruim ao usuario, e terminal-porem-errado passa batido pelos"
echo "       criterios 2 e 3, que so olham se houve desfecho."

# ---------------------------------------------------------------------------------------
passo "3. Rajada"

# k6 na rede do Compose, falando com `videos:8080` direto: a porta publicada passaria pelo
# docker-proxy, que entraria na medicao de latencia sem fazer parte do sistema medido.
# --user: a imagem do k6 roda como um usuario proprio (uid 12345), que nao consegue
# escrever no diretorio de saida montado do host. Sem isto o `--console-output` morre com
# "permission denied" e o experimento fica sem denominador.
docker run --rm --network "$rede" --user "$(id -u):$(id -g)" \
    -v "$raiz/scripts/carga/injetor.js:/injetor.js:ro" \
    -v "$raiz/scripts/carga/fixtures:/fixtures:ro" \
    -v "$saida:/saida" \
    -e VIDEOS_URL=http://videos:8080 \
    -e KEYCLOAK_URL=http://keycloak:8080 \
    -e USUARIO="$usuario" -e SENHA="$senha" \
    -e ARQUIVO="/fixtures/$fixture" \
    -e VUS="$vus" -e ENVIOS="$envios" \
    grafana/k6:latest run --quiet --console-output=/saida/injetor.log /injetor.js \
    > "$saida/k6.out" 2> "$saida/k6.err" &
k6_pid=$!

if [[ "$modo" == mata-videos ]]; then
    # Durante a rajada, e nao depois: a janela que o ADR 0003 fecha e a que existe entre
    # gravar a linha e publicar o comando, e ela so esta aberta enquanto ha envio em voo.
    sleep 3
    alvo="$("${compose[@]}" ps -q videos | head -1)"
    docker kill "$alvo" >/dev/null
    aviso "videos morto a 3s do inicio da rajada (container ${alvo:0:12})"
fi

wait "$k6_pid" && k6_codigo=0 || k6_codigo=$?
cat "$saida/k6.out"
(( k6_codigo == 0 )) || aviso "k6 encerrou com codigo $k6_codigo (esperado quando a borda cai)"

aceitos_arquivo="$saida/aceitos.txt"
sed -n 's/.*ACEITO \([0-9a-f-]*\).*/\1/p' "$saida/injetor.log" | sort -u > "$aceitos_arquivo"
aceitos="$(wc -l < "$aceitos_arquivo")"
recusados="$(grep -c 'RECUSADO' "$saida/injetor.log" || true)"

echo "    aceitos (202) ...: $aceitos"
echo "    recusados .......: $recusados"
if (( recusados > 0 )); then
    grep -o 'RECUSADO.*' "$saida/injetor.log" | sort | uniq -c | sort -rn | sed 's/^/        /'
fi

if [[ "$modo" == mata-videos ]]; then
    "${compose[@]}" up -d videos > /dev/null 2>&1
    aviso "videos de volta — a partir daqui a varredura de reconciliacao e quem responde"
fi

# ---------------------------------------------------------------------------------------
passo "4. Drenagem"

if [[ "$modo" == mata-extracao ]]; then
    sleep 5
    alvo="$("${compose[@]}" ps -q extracao | head -1)"
    docker kill "$alvo" >/dev/null
    aviso "uma replica do extracao morta no meio da drenagem (container ${alvo:0:12})"
    "${compose[@]}" up -d extracao > /dev/null 2>&1
    aviso "replica de volta"
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
passo "5. Censo final e amostra pela API"

censo="$(scripts/carga/oraculo.sh censo "$aceitos_arquivo")"
sed 's/^/    /' <<< "$censo"
tee "$saida/censo.txt" >/dev/null <<< "$censo"

conta() { awk -v e="$1" '$1==e{print $2}' <<< "$censo"; }
concluidos="$(conta CONCLUIDO)"; falhados="$(conta FALHOU)"
ausentes="$(conta AUSENTE)"; recebidos="$(conta RECEBIDO)"; processando="$(conta PROCESSANDO)"
: "${concluidos:=0}" "${falhados:=0}" "${ausentes:=0}" "${recebidos:=0}" "${processando:=0}"

echo
if (( aceitos > 0 )); then
    scripts/carga/oraculo.sh amostra "$aceitos_arquivo" "$(( amostra < aceitos ? amostra : aceitos ))" \
        && amostra_ok=true || amostra_ok=false
else
    amostra_ok=true
fi

# ---------------------------------------------------------------------------------------
passo "6. Veredito"

reprovacoes=0
julga() {
    local nome="$1" condicao="$2" detalhe="$3"
    if [[ "$condicao" == true ]]; then ok "$nome — $detalhe"
    else echo "    ${vermelho}REPROVA${normal}  $nome — $detalhe"; reprovacoes=$((reprovacoes + 1)); fi
}

if [[ "$modo" == mata-videos ]]; then
    aviso "1. Zero nao-202 — dispensado neste modo ($recusados recusas, a borda foi derrubada)"
else
    julga "1. Zero nao-202" "$([[ $recusados == 0 ]] && echo true || echo false)" \
          "$recusados recusas em $envios envios"
fi
julga "2. Todos terminais" "$([[ $(( concluidos + falhados )) == "$aceitos" ]] && echo true || echo false)" \
      "$(( concluidos + falhados ))/$aceitos em ${drenagem}s (limite ${limite_drenagem}s)"
julga "3. Zero presos" "$([[ $(( recebidos + processando + ausentes )) == 0 ]] && echo true || echo false)" \
      "$recebidos RECEBIDO, $processando PROCESSANDO, $ausentes AUSENTE"
julga "4. Amostra pela API" "$amostra_ok" "$amostra ids conferidos como dono"
julga "5. Zero FALHOU" "$([[ $falhados == 0 ]] && echo true || echo false)" \
      "$falhados Video(s) validos declarados FALHOU"

echo
if (( reprovacoes == 0 )); then
    echo "${negrito}${verde}Conservacao preservada${normal} no modo $modo: $aceitos aceitos, $aceitos com desfecho."
else
    echo "${negrito}${vermelho}$reprovacoes criterio(s) reprovado(s)${normal} no modo $modo."
fi
echo "    Saida completa: scripts/carga/saida/$rotulo/"
exit $(( reprovacoes > 0 ? 1 : 0 ))
