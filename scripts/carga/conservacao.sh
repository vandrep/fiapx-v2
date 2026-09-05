#!/usr/bin/env bash
# Experimento de conservacao sob pico (ticket 025).
#
# A afirmacao sob julgamento e a que o enunciado cobra direto: *"em caso de picos, o sistema
# nao deve perder uma requisicao"*. Conservacao nao e vazao — e um invariante de correcao, e
# um teste de carga so serve para *falsea-lo*. Por isso aqui nao se otimiza nada: mede-se se
# algum envio ficou sem desfecho.
#
# Quatro modos, cada um com o seu criterio impresso ANTES de rodar. Sem limiar declarado
# antes, todo resultado vira narrativa pos-fato:
#
#   limpo           rajada sem falha injetada. A fila absorve — todo mundo sabe —, e e por
#                   isso que este modo sozinho prova pouco. Ele e a linha de base.
#   mata-extracao   `docker kill` numa replica do extracao durante a drenagem. Exercita ack
#                   manual, requeue e x-delivery-limit: "o worker morre no meio", que a doc
#                   afirma e nada verificava.
#   mata-videos     `docker kill videos` durante a rajada. E o unico modo que exercita a
#                   varredura de reconciliacao e as colunas marcadoras do ADR 0003 — aquele
#                   ADR existe inteiro para fechar a janela entre gravar e publicar, e essa
#                   janela nunca tinha sido aberta de verdade.
#   redeploy-extracao  recria o `extracao` com `docker compose up -d --force-recreate` no meio
#                   da drenagem — SIGTERM, nao SIGKILL. E o unico modo que julga o
#                   DrenoDaExtracao (ticket 035): mede se a contagem de reentregas da
#                   `extracao.extrair` ficou parada, ou seja, se o deploy custou zero das
#                   tres entregas do x-delivery-limit. Mede tambem quanto o `up -d` demorou,
#                   que e a janela de indisponibilidade cobrada por deploy (ticket 028).
#
#   mata-publicacao sobe o `extracao` com o canal `extracao-falhou` apontando para uma
#                   exchange inexistente (`exchange.declare=false`), envia videos de formato
#                   invalido e mede se a falha definitiva para no estacionamento em vez de
#                   circular pela DLQ para sempre ou sumir em silencio (ticket 029). Unico
#                   modo que nao julga por estado terminal em `videos` — o video correspondente
#                   fica preso em PROCESSANDO de proposito, e e essa a garantia sob teste.
#
# Uso:
#   scripts/carga/conservacao.sh [limpo|mata-extracao|redeploy-extracao|mata-videos|mata-publicacao] [envios]
#
# Variaveis: FIAPX_VUS (default = envios, rajada instantanea), FIAPX_EXTRACAO_REPLICAS,
#            FIAPX_FIXTURE, FIAPX_AMOSTRA, FIAPX_LIMITE_ESTACIONAMENTO (so mata-publicacao).
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

modo="${1:-limpo}"
# mata-publicacao nao e rajada: cada envio ocupa o worker por ~100s ate estacionar (ver
# limite_estacionamento abaixo), e serializa atras do max-outstanding-messages=1 por
# replica. 400 nesse modo seriam horas, nao minutos — por isso o default e outro.
if [[ "$modo" == mata-publicacao ]]; then
    envios="${2:-${FIAPX_ENVIOS:-3}}"
    # FIAPX_ENVIOS e compartilhado com os outros modos de proposito (mesma logica do
    # FIAPX_FIXTURE, FIAPX_VUS etc.) — mas um valor grande deixado exportado de uma rodada
    # anterior de outro modo vira horas aqui, entao o aviso e o minimo custo para nao passar
    # batido.
    (( envios > 20 )) && echo "AVISO: mata-publicacao com $envios envios pode levar horas (cada um serializa ate ~${FIAPX_LIMITE_ESTACIONAMENTO:-240}s)." >&2
else
    envios="${2:-${FIAPX_ENVIOS:-400}}"
fi
vus="${FIAPX_VUS:-$envios}"
replicas="${FIAPX_EXTRACAO_REPLICAS:-4}"
fixture="${FIAPX_FIXTURE:-controle-3s.mp4}"
amostra="${FIAPX_AMOSTRA:-10}"
# 3 entregas de extrair-video (x-delivery-limit=3) + 1 tentativa de publicacao no consumidor
# da DLQ, cada uma esgotando retry-on-fail-attempts=6/retry-on-fail-interval=5s do publisher
# (~25s de backoff por tentativa) antes de desistir: ~100s no pior caso. O dobro e a folga.
limite_estacionamento="${FIAPX_LIMITE_ESTACIONAMENTO:-240}"
rabbitmq_url="${FIAPX_RABBITMQ_URL:-http://localhost:15672}"
rabbitmq_usuario="${FIAPX_RABBITMQ_USUARIO:-fiapx}"
rabbitmq_senha="${FIAPX_RABBITMQ_SENHA:-fiapx}"
# Quando derrubar o `videos`, contado do inicio da rajada. Precisa cair DEPOIS de o primeiro
# 202 ter sido gravado e ANTES de a rajada acabar — cedo demais e a borda morre antes de
# aceitar qualquer coisa, e a rodada nao exercita janela nenhuma (criterio 0). Era 3s fixo
# ate o ticket 027, quando duas rodadas seguidas aceitaram zero.
atraso_kill="${FIAPX_ATRASO_KILL:-3}"
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"

# Tempo de servico de um Video do fixture de controle numa replica, medido no proprio
# harness: 400 envios drenaram em 98 s com 4 replicas. E o numero que da o "tempo esperado
# de drenagem" do criterio 2 — e ele precisa ser o medido, nao um chute generoso: um limite
# folgado demais transforma o criterio 2 em "eventualmente termina", que nada reprova.
# Sobrescrevivel porque o numero e por fixture, nao por sistema: 1 s vale para o
# controle-3s.mp4 (o default de todos os modos). Rodar o redeploy-extracao com o
# carga-2min.mp4 — que e o unico jeito de o dreno segurar por segundos em vez de
# milissegundos — muda o tempo de servico por Video em uma ordem de grandeza, e um limite de
# drenagem calculado sobre 1 s reprovaria o criterio 2 por aritmetica, nao por defeito.
segundos_por_video="${FIAPX_SEGUNDOS_POR_VIDEO:-1}"

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
# So mata-publicacao: le direto do management API, nao do censo (que e por id no Postgres e
# nao enxerga fila nenhuma). Usado duas vezes — antes de enviar (linha de base) e depois
# (medida) — porque a fila e persistente entre corridas e nada aqui faz `down -v`: sem a
# linha de base, mensagens de uma rodada anterior fariam o criterio 3 passar sem medir nada.
# So redeploy-extracao: reentregas acumuladas da extracao.extrair desde que a fila nasceu. E a
# unica medida direta de "o deploy gastou uma entrega" — o x-delivery-limit conta entregas, e o
# broker reenfileira toda entrega nao ackeada quando o canal fecha. Lido antes e depois pelo
# mesmo motivo do estacionamento: a fila e persistente entre corridas e nada aqui faz `down -v`.
# `// 0` cobre a fila que ainda nao teve nenhuma reentrega, em que o RabbitMQ omite a chave.
reentregas_extrair() {
    curl -sS -u "$rabbitmq_usuario:$rabbitmq_senha" \
        "$rabbitmq_url/api/queues/%2F/extracao.extrair" \
        | jq -r '.message_stats.redeliver // 0'
}

profundidade_estacionamento() {
    curl -sS -u "$rabbitmq_usuario:$rabbitmq_senha" \
        "$rabbitmq_url/api/queues/%2F/extracao.extrair.estacionamento" \
        | jq -r '.messages // 0'
}

case "$modo" in limpo|mata-extracao|redeploy-extracao|mata-videos|mata-publicacao) ;; *) falha "modo desconhecido: $modo" ;; esac

# ---------------------------------------------------------------------------------------
passo "0. Dependencias e fixtures"

for ferramenta in docker curl jq shuf; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done
rm -rf "$saida"; mkdir -p "$saida"
if [[ "$modo" == mata-publicacao ]]; then
    # Extensao e content-type precisam passar pela borda (docs/contratos/http-videos.md §
    # Rejeicoes): o que faz este arquivo falhar e o ffprobe do `extracao`, nao o `videos`.
    fixture="invalido.mp4"
    # fixtures/ e gerado por gera-fixtures.sh (.gitignore) e este modo nao o chama — sem o
    # mkdir, a primeira rodada num clone novo falha aqui em vez de escrever o arquivo.
    mkdir -p scripts/carga/fixtures
    echo "isto nao e um video" > "scripts/carga/fixtures/$fixture"
    ok "fixture $fixture (formato invalido de proposito), saida em scripts/carga/saida/$rotulo/"
else
    scripts/carga/gera-fixtures.sh
    [[ -f "scripts/carga/fixtures/$fixture" ]] || falha "fixture $fixture nao existe"
    ok "fixture $fixture, saida em scripts/carga/saida/$rotulo/"
fi

# ---------------------------------------------------------------------------------------
passo "1. Stack de pe com $replicas replicas do extracao"

if [[ "$modo" == mata-publicacao ]]; then
    # So aqui: quebra o canal de saida extracao-falhou do extracao (docker-compose.carga.yml),
    # para que toda publicacao de ExtracaoFalhou falhe e force o caminho ate o estacionamento.
    # Exportadas com o nome que o container le, e so neste modo: na forma de lista do
    # docker-compose.carga.yml, variavel ausente aqui e variavel ausente la — que e o que os
    # outros modos precisam para usar os defaults do application.properties (ticket 038).
    export FIAPX_EXTRACAO_FALHOU_EXCHANGE_NAME="extracao.eventos.inexistente"
    export FIAPX_EXTRACAO_FALHOU_EXCHANGE_DECLARE="false"
fi

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

if [[ "$modo" == mata-publicacao ]]; then
    profundidade_base="$(profundidade_estacionamento)"
    ok "estacionamento antes do envio: $profundidade_base mensagem(ns) ja presentes"
fi

if [[ "$modo" == redeploy-extracao ]]; then
    reentregas_base="$(reentregas_extrair)"
    ok "reentregas de extracao.extrair antes do envio: $reentregas_base"
fi

# ---------------------------------------------------------------------------------------
passo "2. Criterio, fixado antes de rodar"

if [[ "$modo" == mata-publicacao ]]; then
    echo "    modo ............: $modo"
    echo "    envios ..........: $envios videos de formato invalido ($fixture)"
    echo "    limite ..........: ${limite_estacionamento}s por video ate estacionar"
    echo
    echo "    1. Zero respostas nao-202 aos envios — o defeito injetado e na publicacao de"
    echo "       saida do extracao, nao na borda do videos."
    echo "    2. Cada video aceito aparece em PROCESSANDO no censo, e la permanece — o"
    echo "       ExtracaoFalhou nunca alcanca o videos neste modo, de proposito (ticket 029:"
    echo "       'O Video continua preso, e isso e aceite explicito')."
    echo "    3. A extracao.extrair.estacionamento ganha ao menos $envios mensagens NOVAS"
    echo "       (acima da linha de base medida antes do envio) em ate"
    echo "       ${limite_estacionamento}s — nem sumiu (perda silenciosa, o defeito original)"
    echo "       nem ficou circulando para sempre (o loop que o failure-strategy=fail teria"
    echo "       virado)."
    echo
else
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
if [[ "$modo" == mata-videos ]]; then
    echo "    0. Ao menos um 202 ANTES da queda — sem isso nao ha janela aberta para o ADR 0003"
    echo "       fechar, e os criterios 2 a 5 passam com 0/0 sem julgar coisa nenhuma. Portao"
    echo "       de validade da rodada, nao do sistema (ticket 027)."
fi
echo "    2. 100% dos ids aceitos em estado terminal (CONCLUIDO ou FALHOU) em ${limite_drenagem}s."
echo "    3. Zero presos em RECEBIDO ou PROCESSANDO ao fim."
echo "    4. Amostra de $amostra ids conferida pela API, como dono: 200 e estado terminal."
echo "    5. Zero FALHOU. O fixture e um h264 valido de $fixture — todo FALHOU aqui e um"
echo "       Video bom declarado ruim ao usuario, e terminal-porem-errado passa batido pelos"
echo "       criterios 2 e 3, que so olham se houve desfecho."
if [[ "$modo" == redeploy-extracao ]]; then
    echo "    6. ZERO reentregas novas em extracao.extrair. E o criterio do ticket 035, e o unico"
    echo "       que os criterios 2 a 5 nao alcancam: uma Extracao morta pelo SIGTERM e"
    echo "       reenfileirada e chega a CONCLUIDO na segunda entrega, verde nos outros"
    echo "       criterios e com uma das tres entregas gasta. Antes do DrenoDaExtracao este"
    echo "       numero era >= 1 por replica recriada com Extracao em voo (ticket 030)."
    echo "    7. O 'docker compose up -d' e reportado com o tempo que levou. Nao ha limiar aqui:"
    echo "       e o custo de janela por deploy que o ticket 028 precisa conhecer, medido e nao"
    echo "       julgado. O teto teorico e o stop_grace_period de 480s; o esperado e o que"
    echo "       sobra da Extracao em voo."
fi
fi

# ---------------------------------------------------------------------------------------
passo "3. Rajada"

if [[ "$modo" == mata-publicacao ]]; then
    # Poucos envios, sequenciais, via curl direto — nao e rajada, e o k6 (feito para medir
    # latencia de centenas de conexoes) so acrescentaria complexidade sem medir nada aqui.
    aceitos_arquivo="$saida/aceitos.txt"
    : > "$aceitos_arquivo"
    recusados=0

    token="$(curl -sS -X POST "http://localhost:8081/realms/fiapx/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=fiapx-videos \
        -d "username=$usuario" -d "password=$senha" | jq -r '.access_token // empty')"
    [[ -n "$token" ]] || falha "Keycloak nao devolveu token para $usuario"

    for _ in $(seq 1 "$envios"); do
        resposta="$(curl -sS -o "$saida/envio.json" -w '%{http_code}' \
            -X POST "http://localhost:8080/videos" \
            -H "Authorization: Bearer $token" \
            -F "arquivo=@scripts/carga/fixtures/$fixture;type=video/mp4;filename=$fixture")"
        if [[ "$resposta" == 202 ]]; then
            jq -r '.id' "$saida/envio.json" >> "$aceitos_arquivo"
        else
            recusados=$((recusados + 1))
            aviso "envio recusado: HTTP $resposta"
        fi
    done

    aceitos="$(wc -l < "$aceitos_arquivo")"
    echo "    aceitos (202) ...: $aceitos"
    echo "    recusados .......: $recusados"
else
# k6 na rede do Compose, falando com `videos:8080` direto: a porta publicada passaria pelo
# docker-proxy, que entraria na medicao de latencia sem fazer parte do sistema medido.
# --user: a imagem do k6 roda como um usuario proprio (uid 12345), que nao consegue
# escrever no diretorio de saida montado do host. Sem isto o `--console-output` morre com
# "permission denied" e o experimento fica sem denominador.
#
# Qual uid depende de o daemon ser rootless (ticket 036, e achado ao rodar no ticket 035):
# sob rootless, o usuario do host ja entra no user namespace como 0, entao o diretorio de
# saida — dono 1000 do lado de fora — aparece como dono 0 dentro do container, e `--user 1000`
# vira um estranho sem permissao de escrita. Com daemon rootful vale o inverso, e `--user 0`
# deixaria a saida do experimento com dono root no repositorio. Nao da para fixar um dos dois:
# a resposta certa e a pergunta ao daemon.
if docker info -f '{{.SecurityOptions}}' 2>/dev/null | grep -q 'name=rootless'; then
    k6_usuario="0:0"
else
    k6_usuario="$(id -u):$(id -g)"
fi
docker run --rm --network "$rede" --user "$k6_usuario" \
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
    sleep "$atraso_kill"
    alvo="$("${compose[@]}" ps -q videos | head -1)"
    docker kill "$alvo" >/dev/null
    aviso "videos morto a ${atraso_kill}s do inicio da rajada (container ${alvo:0:12})"
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
fi

# ---------------------------------------------------------------------------------------
passo "4. Drenagem"

if [[ "$modo" == mata-publicacao ]]; then
    inicio_espera=$SECONDS
    novas=0
    while :; do
        profundidade="$(profundidade_estacionamento)"
        novas=$(( profundidade - profundidade_base ))
        decorrido=$(( SECONDS - inicio_espera ))
        printf '    %4ds  estacionamento=%s/%s (base %s, total %s)\n' \
            "$decorrido" "$novas" "$aceitos" "$profundidade_base" "$profundidade"
        (( novas >= aceitos )) && break
        (( decorrido > limite_estacionamento )) && { aviso "limite de ${limite_estacionamento}s estourado"; break; }
        sleep 5
    done
    espera=$(( SECONDS - inicio_espera ))
elif [[ "$modo" == mata-extracao ]]; then
    sleep 5
    alvo="$("${compose[@]}" ps -q extracao | head -1)"
    docker kill "$alvo" >/dev/null
    aviso "uma replica do extracao morta no meio da drenagem (container ${alvo:0:12})"
    "${compose[@]}" up -d extracao > /dev/null 2>&1
    aviso "replica de volta"
elif [[ "$modo" == redeploy-extracao ]]; then
    # Nao um `sleep` fixo: o redeploy tem que cair com Extracao EM VOO, e "5 segundos depois
    # da rajada" nao garante isso — na primeira rodada deste modo a fila ja tinha drenado
    # metade nesse prazo e o redeploy pegou as replicas ociosas, medindo zero reentrega sem
    # ter exercitado dreno nenhum. Espera-se pela condicao, e ela e observavel:
    # messages_unacknowledged e exatamente "entregue e ainda sem ack", que com
    # max-outstanding-messages=1 e o mesmo que "Extracao em voo".
    em_voo_antes=0
    inicio_espera_voo=$SECONDS
    while (( SECONDS - inicio_espera_voo < 60 )); do
        em_voo_antes="$(curl -sS -u "$rabbitmq_usuario:$rabbitmq_senha" \
            "$rabbitmq_url/api/queues/%2F/extracao.extrair" | jq -r '.messages_unacknowledged // 0')"
        (( em_voo_antes > 0 )) && break
        sleep 1
    done
    # O log das replicas QUE VAO MORRER, seguido em segundo plano: `--force-recreate` remove o
    # container velho, e depois dele `docker logs` nao alcanca mais nada. Sem isto, "o dreno
    # segurou o desligamento" e inferencia a partir da contagem de reentregas; com ele e a
    # propria linha que o DrenoDaExtracao escreve antes de esperar. E a evidencia direta do
    # ticket 035, e ela so existe se for capturada antes.
    : > "$saida/dreno.log"
    for velho in $("${compose[@]}" ps -q extracao); do
        docker logs -f --since 1s "$velho" >> "$saida/dreno.log" 2>&1 &
    done

    # --force-recreate porque um `up -d` sem mudanca nenhuma e no-op: sem imagem nova, e ele
    # que reproduz o que um deploy faz — para o container com SIGTERM, respeitando o
    # stop_grace_period, e sobe outro no lugar. Sem ele nao ha SIGTERM e nao ha o que medir.
    inicio_redeploy=$SECONDS
    "${compose[@]}" up -d --force-recreate extracao > "$saida/redeploy.log" 2>&1 \
        || { cat "$saida/redeploy.log" >&2; falha "redeploy do extracao falhou"; }
    segundos_redeploy=$(( SECONDS - inicio_redeploy ))
    wait $(jobs -rp) 2>/dev/null || true
    linhas_dreno="$(grep -c 'segurando o desligamento' "$saida/dreno.log" || true)"
    aviso "extracao recriado em ${segundos_redeploy}s com $em_voo_antes mensagem(ns) sem ack no inicio"
    aviso "$linhas_dreno replica(s) registraram o dreno segurando o desligamento (scripts/carga/saida/$rotulo/dreno.log)"
fi

if [[ "$modo" != mata-publicacao ]]; then
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
fi

if [[ "$modo" == redeploy-extracao ]]; then
    reentregas_novas=$(( $(reentregas_extrair) - reentregas_base ))
fi

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
if [[ "$modo" == mata-publicacao ]]; then
    # Nao ha estado terminal para conferir pela API neste modo — o video fica em PROCESSANDO
    # de proposito (ver criterio 2 acima). O censo acima ja e a prova; nao ha amostra a fazer.
    amostra_ok=true
elif (( aceitos > 0 )); then
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

if [[ "$modo" == mata-publicacao ]]; then
    julga "1. Zero nao-202" "$([[ $recusados == 0 ]] && echo true || echo false)" \
          "$recusados recusas em $envios envios"
    julga "2. Todos presos em PROCESSANDO" "$([[ $processando == "$aceitos" && $concluidos == 0 && $falhados == 0 && $ausentes == 0 ]] && echo true || echo false)" \
          "$processando/$aceitos em PROCESSANDO, $concluidos CONCLUIDO, $falhados FALHOU, $ausentes AUSENTE"
    julga "3. Estacionamento recebeu a falha definitiva" "$([[ $novas -ge $aceitos ]] && echo true || echo false)" \
          "$novas/$aceitos mensagens novas em extracao.extrair.estacionamento em ${espera}s (base $profundidade_base, limite ${limite_estacionamento}s)"
    echo
    if (( reprovacoes == 0 )); then
        echo "${negrito}${verde}Falha definitiva parou no estacionamento${normal} no modo $modo: $aceitos aceitos, $novas estacionados."
    else
        echo "${negrito}${vermelho}$reprovacoes criterio(s) reprovado(s)${normal} no modo $modo."
    fi
    echo "    Saida completa: scripts/carga/saida/$rotulo/"
    exit $(( reprovacoes > 0 ? 1 : 0 ))
fi

if [[ "$modo" == mata-videos ]]; then
    # Portao de validade da RODADA, e nao criterio sobre o sistema: com zero aceitos os
    # criterios 2 a 5 passam todos com 0/0 e a rodada nao julgou nada. Aconteceu duas vezes
    # seguidas no ticket 027 — a borda caiu antes do primeiro 202 — e passou verde.
    if (( aceitos == 0 )); then
        echo "    ${vermelho}INVALIDA${normal}  0. Nenhum 202 antes da queda: nenhuma janela foi aberta."
        echo
        echo "${negrito}${vermelho}Rodada invalida${normal}: aumente FIAPX_ATRASO_KILL (atual ${atraso_kill}s) e repita."
        echo "    Saida completa: scripts/carga/saida/$rotulo/"
        exit 2
    fi
    ok "0. Rodada valida — $aceitos aceito(s) antes da queda"
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
if [[ "$modo" == redeploy-extracao ]]; then
    if (( em_voo_antes == 0 )); then
        echo "    ${vermelho}INVALIDA${normal}  0. Nenhuma Extracao em voo no momento do redeploy: nao havia o"
        echo "              que drenar, e o criterio 6 passaria verde sem julgar nada."
        echo
        echo "${negrito}${vermelho}Rodada invalida${normal}: aumente FIAPX_ENVIOS (atual $envios) para a fila nao"
        echo "    drenar antes do redeploy, e repita."
        echo "    Saida completa: scripts/carga/saida/$rotulo/"
        exit 2
    fi
    ok "0. Rodada valida — $em_voo_antes Extracao(oes) em voo quando o SIGTERM chegou"
    julga "6. Zero reentregas" "$([[ $reentregas_novas == 0 ]] && echo true || echo false)" \
          "$reentregas_novas reentrega(s) nova(s) em extracao.extrair (base $reentregas_base), com $em_voo_antes sem ack no momento do redeploy"
    julga "6b. O dreno realmente rodou" "$([[ $linhas_dreno -gt 0 ]] && echo true || echo false)" \
          "$linhas_dreno replica(s) logaram o dreno segurando o desligamento; sem essa linha, um criterio 6 verde nao prova o mecanismo, so que ninguem estava trabalhando"
    ok "7. Janela de deploy — ${segundos_redeploy}s no 'up -d --force-recreate' (teto: stop_grace_period 480s), medido e nao julgado"
fi

echo
if (( reprovacoes == 0 )); then
    echo "${negrito}${verde}Conservacao preservada${normal} no modo $modo: $aceitos aceitos, $aceitos com desfecho."
else
    echo "${negrito}${vermelho}$reprovacoes criterio(s) reprovado(s)${normal} no modo $modo."
fi
echo "    Saida completa: scripts/carga/saida/$rotulo/"
exit $(( reprovacoes > 0 ? 1 : 0 ))
