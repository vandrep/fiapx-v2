#!/usr/bin/env bash
# Experimento de linearidade horizontal do `extracao` (ticket 026).
#
# A afirmacao sob julgamento e a da tabela § *O que escala, e como* de `docs/arquitetura.md`:
# *"competing consumers puro: dobrar replicas dobra a vazao"*. O criterio, fixado antes de
# rodar, e eficiencia de escala `vazao(N) / (N * vazao(1)) >= 0,8`, e o experimento **relata**
# o primeiro N em que ela quebra em vez de reprovar ali.
#
# Irmao do `conservacao.sh` (ticket 025), e nao uma extensao dele: aquele julga um invariante
# de *correcao* (nada se perde) e este mede uma *grandeza* (vazao). Compartilham
# `gera-fixtures.sh`, `injetor.js` e `oraculo.sh` sem um toque; o que muda e o denominador,
# o protocolo entre pontos e os portoes de validade.
#
# O metodo esta pre-registrado na secao "Metodo, fixado antes de rodar" do ticket 026, escrita
# antes da primeira medicao. Onde este script e o ticket divergirem, o ticket manda — e a
# divergencia e achado, nao detalhe de implementacao.
#
# Subcomandos:
#   calibra              mede o tempo de servico e a particao dele, e congela a quantidade de
#                        Videos que todos os pontos vao usar (item 1 do metodo).
#   ponto <N> <rep>      uma corrida: `down -v`, sobe N replicas, injeta, drena, julga.
#   seco                 calibra + um unico ponto N=1. E o passo 2 da ordem de execucao: se a
#                        particao desmentir a conta do ticket 006, metade das decisoes do
#                        metodo merece revisao antes de gastar a maquina.
#   varredura            a coisa toda: calibracao, N em {1,2,4,6} x 2 repeticoes em ordem
#                        randomizada, e os dois controles finais em N=1.
#   resumo               le as saidas ja gravadas e imprime a tabela, a curva e a eficiencia.
#
# AVISO: `ponto` e `varredura` rodam `docker compose down -v`, que **destroi os volumes**
# (Postgres, MinIO, RabbitMQ, scratch). Item 4 do metodo: comparar vazao entre N=1 e N=6 com
# banco e bucket carregando lixo da corrida anterior e comparar duas maquinas diferentes.
#
# Variaveis: FIAPX_VUS (default 16), FIAPX_EXTRACAO_CPUS (default 2), FIAPX_FIXTURE,
#            FIAPX_VIDEOS (sobrepoe a calibracao), FIAPX_ALVO_SEGUNDOS (default 720).
set -euo pipefail

# O `awk` sob locale pt_BR le "1787774374.011" cortando no ponto decimal, o que transformava
# toda subtracao de instante em segundo inteiro e toda vazao em numero com virgula. Achado na
# primeira calibracao do ticket 026: a particao saiu com granularidade de 1 s e nenhuma etapa
# rapida era distinguivel de zero. Medicao com separador decimal ambiguo nao e medicao.
export LC_ALL=C

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

fixture="${FIAPX_FIXTURE:-carga-2min.mp4}"
vus="${FIAPX_VUS:-16}"
cpus="${FIAPX_EXTRACAO_CPUS:-2}"
alvo_segundos="${FIAPX_ALVO_SEGUNDOS:-720}"   # item 1: ~12 min de drenagem em N=1
usuario="${FIAPX_USUARIO:-demo}"
senha="${FIAPX_SENHA:-demo}"
amostra="${FIAPX_AMOSTRA:-10}"

base="$raiz/scripts/carga/saida/escalabilidade"
calibracao_env="$base/calibracao.env"
rede="$(basename "$raiz")_default"
export FIAPX_EXTRACAO_CPUS="$cpus"
compose=(docker compose -f docker-compose.yml -f docker-compose.carga.yml)

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; amarelo=$'\e[33m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; amarelo=''; normal=''
fi
passo()  { echo; echo "${negrito}==> $*${normal}"; }
ok()     { echo "    ${verde}OK${normal}  $*"; }
aviso()  { echo "    ${amarelo}!${normal}   $*"; }
falha()  { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

psql_() { "${compose[@]}" exec -T postgres psql -U fiapx -d fiapx_videos -q -t -A -F' ' -v ON_ERROR_STOP=1; }

# ---------------------------------------------------------------------------------------
# Infraestrutura de corrida

exige_ferramentas() {
    for ferramenta in docker curl jq shuf awk; do
        command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
    done
    scripts/carga/gera-fixtures.sh
    [[ -f "scripts/carga/fixtures/$fixture" ]] || falha "fixture $fixture nao existe"
}

derruba() { "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }

sobe() {
    local n="$1" registro="$2"
    FIAPX_EXTRACAO_REPLICAS="$n" "${compose[@]}" up -d > "$registro" 2>&1 \
        || { cat "$registro" >&2; falha "compose up falhou"; }

    # Mesma razao do smoke.sh para nao usar `up --wait`: o minio-seed e one-shot e sai com 0,
    # o que o --wait le como servico morto.
    local esperados=$(( 2 + n )) inicio=$SECONDS estado saudaveis
    while :; do
        estado="$(FIAPX_EXTRACAO_REPLICAS="$n" "${compose[@]}" ps --format '{{.Service}} {{.Health}}' \
            | grep -E '^(videos|extracao|notificacao) ' || true)"
        saudaveis="$(grep -c ' healthy$' <<< "$estado" || true)"
        [[ "$saudaveis" == "$esperados" ]] && break
        (( SECONDS - inicio > 300 )) && falha "servicos nao ficaram saudaveis:"$'\n'"$estado"
        sleep 3
    done
}

# Item 5: o defeito 3 do 025 (`limparOrfaosNoBoot` apaga o scratch das replicas vivas) e
# contornado por protocolo, nao corrigido — nada pode bootar com trabalho em voo. A assinatura
# abaixo e `StartedAt` de cada container: qualquer mudanca entre o inicio e o fim da corrida e
# um boot no meio, e a corrida inteira deixa de valer.
assinatura_containers() {
    local id
    for id in $("${compose[@]}" ps -q 2>/dev/null); do
        docker inspect -f '{{.Name}} {{.State.StartedAt}} {{.State.Status}} {{.RestartCount}}' "$id"
    done | sort
}

# Item 7: sem telemetria, "achatou porque os 20 cores acabaram" e exatamente o tipo de frase
# que o 025 se recusou a aceitar sobre conservacao.
telemetria_pid=''
telemetria_inicia() {
    local destino="$1"
    : > "$destino"
    (
        while :; do
            # `cpu` do /proc/stat: contadores acumulados do HOST. Sem ele, "o host nao era o
            # teto" e inferencia a partir da soma das medias por container — e a soma nao fecha,
            # porque a leitura de CPU do container do RabbitMQ oscila entre 0,3% e 894% em
            # amostras de 2 s sem correlacao com trabalho (medido no 026). Ocioso do host vem de
            # uma fonte que nao depende de nenhum container.
            printf '%s load=%s host=%s\n' "$(date +%s)" "$(cut -d' ' -f1 /proc/loadavg)" \
                "$(awk '/^cpu /{print $2"_"$3"_"$4"_"$5"_"$6"_"$7"_"$8}' /proc/stat)"
            docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null \
                | sed 's/^/  /'
            sleep 5
        done
    ) >> "$destino" 2>/dev/null &
    telemetria_pid=$!
}
telemetria_para() {
    [[ -n "$telemetria_pid" ]] || return 0
    kill "$telemetria_pid" 2>/dev/null || true
    wait "$telemetria_pid" 2>/dev/null || true
    telemetria_pid=''
}
trap telemetria_para EXIT

injeta() {
    local envios="$1" concorrencia="$2" saida="$3"
    # O `--console-output` do k6 APENDA. Sem truncar, uma segunda injecao no mesmo diretorio
    # herda os ids da primeira e o denominador do experimento passa a contar Videos que o
    # `down -v` ja apagou — a drenagem entao nunca fecha. Achado na primeira varredura do 026.
    rm -f "$saida/injetor.log"
    docker run --rm --network "$rede" --user "$(id -u):$(id -g)" \
        -v "$raiz/scripts/carga/injetor.js:/injetor.js:ro" \
        -v "$raiz/scripts/carga/fixtures:/fixtures:ro" \
        -v "$saida:/saida" \
        -e VIDEOS_URL=http://videos:8080 \
        -e KEYCLOAK_URL=http://keycloak:8080 \
        -e USUARIO="$usuario" -e SENHA="$senha" \
        -e ARQUIVO="/fixtures/$fixture" \
        -e VUS="$concorrencia" -e ENVIOS="$envios" -e DURACAO_MAXIMA=30m \
        grafana/k6:latest run --quiet --console-output=/saida/injetor.log /injetor.js \
        > "$saida/k6.out" 2> "$saida/k6.err" || aviso "k6 encerrou com codigo $?"
}

copia_ids() { # imprime o preambulo COPY para uma lista de ids
    echo "CREATE TEMP TABLE enviados (id uuid PRIMARY KEY);"
    echo "COPY enviados FROM STDIN;"
    cat "$1"
    echo '\.'
}

# ---------------------------------------------------------------------------------------
calibra() {
    passo "Calibracao (item 1 e item 3 do metodo)"
    exige_ferramentas
    local saida="$base/calibracao"
    rm -rf "$saida"; mkdir -p "$saida"

    derruba
    sobe 1 "$saida/compose.log"
    ok "stack de pe com 1 replica, teto de $cpus CPU"

    # --- tempo de servico: a diferenca entre `finalizado_em` consecutivos em N=1 *e* o tempo
    # de servico em regime. Medir uma extracao isolada mediria tambem o boot frio da JVM.
    local sondas=6
    echo "    injetando $sondas Videos de sonda em serie..."
    injeta "$sondas" 2 "$saida"
    local ids="$saida/aceitos.txt"
    sed -n 's/.*ACEITO \([0-9a-f-]*\).*/\1/p' "$saida/injetor.log" | sort -u > "$ids"
    local aceitos; aceitos="$(wc -l < "$ids")"
    (( aceitos == sondas )) || aviso "sonda: $aceitos de $sondas aceitos"

    local inicio=$SECONDS terminais=0
    while :; do
        terminais="$(scripts/carga/oraculo.sh censo "$ids" \
            | awk '$1=="CONCLUIDO"||$1=="FALHOU"{s+=$2} END{print s+0}')"
        (( terminais == aceitos )) && break
        (( SECONDS - inicio > 900 )) && falha "sonda nao drenou em 15 min"
        sleep 5
    done

    # Descarta a primeira: ela carrega o aquecimento da JVM e do pool de conexoes.
    local medicao
    medicao="$( { copia_ids "$ids"; cat <<'SQL'
SELECT round(avg(delta)::numeric, 2), round(min(delta)::numeric, 2), round(max(delta)::numeric, 2),
       round(avg(frames)::numeric, 0)
  FROM (SELECT extract(epoch FROM v.finalizado_em
                 - lag(v.finalizado_em) OVER (ORDER BY v.finalizado_em)) AS delta,
               v.quantidade_frames AS frames
          FROM enviados e JOIN video v ON v.id = e.id) s
 WHERE delta IS NOT NULL;
SQL
    } | psql_ )"
    local tempo_servico frames
    tempo_servico="$(awk '{print $1}' <<< "$medicao")"
    frames="$(awk '{print $4}' <<< "$medicao")"
    echo "    tempo de servico : ${tempo_servico}s por Video (min $(awk '{print $2}' <<< "$medicao")s, max $(awk '{print $3}' <<< "$medicao")s)"
    echo "    frames por Video : $frames"

    # --- particao do tempo de servico (item 3). Medida FORA do servico, com as ferramentas do
    # proprio container do `extracao` e um `mc` na mesma rede: instrumentar o servico com log
    # de tempo por etapa seria deformar o objeto medido. E aproximacao declarada, nao a
    # instrumentacao que o servico nao tem.
    particao "$saida" || aviso "particao nao pode ser medida"

    local videos
    videos="$(awk -v alvo="$alvo_segundos" -v t="$tempo_servico" 'BEGIN{printf "%d", (alvo/t)+0.5}')"
    (( videos < 8 )) && videos=8

    mkdir -p "$base"
    cat > "$calibracao_env" <<EOF
# Congelado pela calibracao de $(date -Iseconds). A quantidade de Videos e CONSTANTE nos
# quatro pontos (item 1): varia-la por ponto para manter a drenagem constante quebraria a
# comparacao, porque backlogs diferentes tem perfis de contencao diferentes.
CAL_TEMPO_SERVICO=$tempo_servico
CAL_VIDEOS=$videos
CAL_FRAMES=$frames
CAL_FIXTURE=$fixture
CAL_CPUS=$cpus
CAL_ALVO_SEGUNDOS=$alvo_segundos
EOF
    ok "congelado: $videos Videos por ponto (~$(awk -v v="$videos" -v t="$tempo_servico" 'BEGIN{printf "%.0f", v*t/60}') min em N=1, ~$(awk -v v="$videos" -v t="$tempo_servico" 'BEGIN{printf "%.0f", v*t/6/60}') min em N=6)"
    echo "    saida: scripts/carga/saida/escalabilidade/calibracao.env"
}

particao() {
    local saida="$1"
    local container; container="$("${compose[@]}" ps -q extracao | head -1)"
    [[ -n "$container" ]] || return 1
    local bytes_video; bytes_video="$(stat -c %s "scripts/carga/fixtures/$fixture")"

    echo
    echo "    particao do tempo de servico (aproximacao declarada, item 3):"
    docker cp "scripts/carga/fixtures/$fixture" "$container:/tmp/f.mp4" >/dev/null

    local t_probe t_ffmpeg t_pack t_down t_up
    # As etapas rapidas rodam K vezes dentro do container e o relogio e o de dentro: o custo do
    # `docker exec` (dezenas de ms) nao entra, e a media de K amortiza o ruido.
    t_probe="$(docker exec "$container" sh -c '
        s=$(date +%s.%N)
        i=0; while [ $i -lt 10 ]; do
          ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 /tmp/f.mp4 >/dev/null
          ffprobe -v error -select_streams v:0 -show_entries stream=codec_type -of csv=p=0 /tmp/f.mp4 >/dev/null
          i=$((i+1)); done
        e=$(date +%s.%N); echo "$s $e"' | awk '{printf "%.3f", ($2-$1)/10}')"

    t_ffmpeg="$(docker exec "$container" sh -c '
        rm -rf /tmp/frames && mkdir -p /tmp/frames
        s=$(date +%s.%N)
        ffmpeg -hide_banner -nostdin -loglevel level+repeat+error -xerror -nostats \
               -i /tmp/f.mp4 -vf fps=1 -y /tmp/frames/frame_%04d.png >/dev/null 2>&1
        e=$(date +%s.%N); echo "$s $e"' | awk '{printf "%.3f", $2-$1}')"

    # ZIP STORED e copia de bytes com CRC; `tar -cf` e o proxy disponivel dentro da imagem
    # (JRE alpine + ffmpeg, sem `zip`). Aproximacao declarada: o CRC32 do ZIP nao esta aqui.
    t_pack="$(docker exec "$container" sh -c '
        s=$(date +%s.%N)
        i=0; while [ $i -lt 5 ]; do tar -cf /tmp/frames.tar -C /tmp/frames . ; i=$((i+1)); done
        e=$(date +%s.%N); echo "$s $e"' | awk '{printf "%.3f", ($2-$1)/5}')"
    local bytes_pack; bytes_pack="$(docker exec "$container" sh -c 'stat -c %s /tmp/frames.tar')"

    # I/O contra o MinIO: `mc` na mesma rede, mesmos bytes, mesmo caminho de rede. Nao e o
    # `S3AsyncClient` do servico — o numero entra como ordem de grandeza, nao como o tempo
    # exato que o adapter gasta.
    local mc_saida
    mc_saida="$(docker run --rm --network "$rede" --entrypoint sh minio/mc:latest -c "
        mc alias set l http://minio:9000 minioadmin minioadmin >/dev/null 2>&1
        head -c $bytes_video /dev/urandom > /tmp/v.bin
        s=\$(date +%s.%N)
        i=0; while [ \$i -lt 5 ]; do mc cp -q /tmp/v.bin l/videos/particao-sonda-\$i.bin >/dev/null 2>&1; i=\$((i+1)); done
        e=\$(date +%s.%N); echo up \$s \$e
        s=\$(date +%s.%N)
        i=0; while [ \$i -lt 5 ]; do mc cp -q l/videos/particao-sonda-\$i.bin /tmp/v\$i.bin >/dev/null 2>&1; i=\$((i+1)); done
        e=\$(date +%s.%N); echo down \$s \$e
        mc rm --recursive --force l/videos/ >/dev/null 2>&1 || true" 2>/dev/null)"
    t_up="$(awk '$1=="up"{printf "%.3f", ($3-$2)/5}' <<< "$mc_saida")";   : "${t_up:=0}"
    t_down="$(awk '$1=="down"{printf "%.3f", ($3-$2)/5}' <<< "$mc_saida")"; : "${t_down:=0}"
    # O upload do Pacote move menos bytes que o do Video: escala pelo tamanho.
    local t_up_pacote
    t_up_pacote="$(awk -v u="$t_up" -v p="$bytes_pack" -v v="$bytes_video" 'BEGIN{printf "%.3f", u*p/v}')"

    {
        echo "# particao medida em $(date -Iseconds); fixture $fixture ($bytes_video bytes),"
        echo "# pacote aproximado por tar ($bytes_pack bytes)"
        echo "etapa segundos"
        echo "download-video $t_down"
        echo "ffprobe $t_probe"
        echo "ffmpeg $t_ffmpeg"
        echo "empacotamento $t_pack"
        echo "upload-pacote $t_up_pacote"
    } > "$saida/particao.txt"

    awk -v d="$t_down" -v p="$t_probe" -v f="$t_ffmpeg" -v z="$t_pack" -v u="$t_up_pacote" 'BEGIN{
        total=d+p+f+z+u;
        printf "      download do Video ..: %6.2fs  (%4.1f%%)\n", d, 100*d/total;
        printf "      ffprobe (2 chamadas): %6.2fs  (%4.1f%%)\n", p, 100*p/total;
        printf "      ffmpeg .............: %6.2fs  (%4.1f%%)\n", f, 100*f/total;
        printf "      empacotamento ......: %6.2fs  (%4.1f%%)\n", z, 100*z/total;
        printf "      upload do Pacote ...: %6.2fs  (%4.1f%%)\n", u, 100*u/total;
        printf "      soma das etapas ....: %6.2fs\n", total;
        printf "      ffmpeg e %.1f%% do tempo de servico", 100*f/total;
        if (100*f/total < 40) printf "  <-- GATILHO do item 3: abaixo de 40%%, entra fixture longo em N=1 e N=6";
        printf "\n";
    }'
}

# ---------------------------------------------------------------------------------------
ponto() {
    local n="$1" rep="$2" sujo="${3:-limpo}"
    [[ -f "$calibracao_env" ]] || falha 'sem calibracao — rode: escalabilidade.sh calibra'
    # shellcheck disable=SC1090
    source "$calibracao_env"
    local videos="${FIAPX_VIDEOS:-$CAL_VIDEOS}"
    local rotulo="n${n}-r${rep}"
    [[ "$rep" == controle ]] && rotulo="controle-limpo"
    [[ "$sujo" == sujo ]] && rotulo="controle-sujo"
    local saida="$base/$rotulo"
    rm -rf "$saida"; mkdir -p "$saida"

    passo "Ponto $rotulo — N=$n replicas, $cpus CPU cada, $videos Videos de $CAL_FIXTURE"

    if [[ "$sujo" == sujo ]]; then
        aviso 'controle SUJO: sem down -v, sobre o estado acumulado da corrida anterior'
    else
        derruba
        sobe "$n" "$saida/compose.log"
    fi
    ok "$n replica(s) saudavel(is)"

    local assinatura_antes; assinatura_antes="$(assinatura_containers)"
    echo "$assinatura_antes" > "$saida/containers-antes.txt"

    telemetria_inicia "$saida/telemetria.txt"

    local t0=$SECONDS
    injeta "$videos" "$vus" "$saida"
    local duracao_injecao=$(( SECONDS - t0 ))

    local ids="$saida/aceitos.txt"
    sed -n 's/.*ACEITO \([0-9a-f-]*\).*/\1/p' "$saida/injetor.log" | sort -u > "$ids"
    local aceitos recusados
    aceitos="$(wc -l < "$ids")"
    recusados="$(grep -c 'RECUSADO' "$saida/injetor.log" || true)"
    echo "    injecao .........: ${duracao_injecao}s, $aceitos aceitos, $recusados recusados"

    local limite=$(( videos * ${CAL_TEMPO_SERVICO%.*} * 3 / n ))
    (( limite < 300 )) && limite=300

    local inicio=$SECONDS terminais=0 decorrido=0
    while :; do
        local censo
        censo="$(scripts/carga/oraculo.sh censo "$ids")"
        terminais="$(awk '$1=="CONCLUIDO"||$1=="FALHOU"{s+=$2} END{print s+0}' <<< "$censo")"
        decorrido=$(( SECONDS - inicio ))
        printf '    %4ds  %s\n' "$decorrido" "$(tr '\n' ' ' <<< "$censo")"
        (( terminais == aceitos )) && break
        if [[ "$(assinatura_containers)" != "$assinatura_antes" ]]; then
            telemetria_para
            aviso "container reiniciou durante a corrida — item 5 manda abortar"
            echo "INVALIDO=restart" >> "$saida/ponto.env"
            return 1
        fi
        (( decorrido > limite )) && { aviso "limite de ${limite}s estourado"; break; }
        sleep 5
    done
    local drenagem=$(( SECONDS - inicio ))
    telemetria_para

    local assinatura_depois; assinatura_depois="$(assinatura_containers)"
    echo "$assinatura_depois" > "$saida/containers-depois.txt"
    local restarts=0
    [[ "$assinatura_antes" == "$assinatura_depois" ]] || restarts=1

    # --- item 2: vazao e a serie de `finalizado_em` na janela POS-injecao. O fim da injecao e
    # `max(recebido_em)` dos proprios enviados, e nao um relogio lido pelo script: a borda ja
    # registra quando aceitou o ultimo Video, entao a janela sai da mesma fonte da serie que
    # ela recorta. O cronometro de
    # parede vai ao lado, como controle: a rampa de injecao entra no denominador dele
    # penalizando mais o N alto, que drena enquanto ainda se injeta.
    local metricas
    metricas="$( { copia_ids "$ids"; cat <<SQL
SELECT count(*) FILTER (WHERE v.finalizado_em > (SELECT max(recebido_em) FROM enviados e2 JOIN video v2 ON v2.id = e2.id)),
       coalesce(round(extract(epoch FROM max(v.finalizado_em) FILTER (WHERE v.finalizado_em > (SELECT max(recebido_em) FROM enviados e2 JOIN video v2 ON v2.id = e2.id))
                                       - min(v.finalizado_em) FILTER (WHERE v.finalizado_em > (SELECT max(recebido_em) FROM enviados e2 JOIN video v2 ON v2.id = e2.id)))::numeric, 2), 0),
       count(*) FILTER (WHERE v.estado = 'CONCLUIDO'),
       count(*) FILTER (WHERE v.estado = 'FALHOU'),
       count(*) FILTER (WHERE v.estado IN ('RECEBIDO','PROCESSANDO')),
       count(*) FILTER (WHERE v.id IS NULL),
       count(*) FILTER (WHERE v.quantidade_frames IS DISTINCT FROM $CAL_FRAMES),
       coalesce(round(extract(epoch FROM max(v.finalizado_em) - min(v.recebido_em))::numeric, 2), 0)
  FROM enviados e LEFT JOIN video v ON v.id = e.id;
SQL
    } | psql_ )"
    read -r regime_n regime_s concluidos falhados presos ausentes frames_errados parede_s <<< "$metricas"

    local vazao_regime vazao_intervalos vazao_parede
    vazao_regime="$(awk -v n="$regime_n" -v s="$regime_s" 'BEGIN{printf "%.4f", (s>0)? n/s : 0}')"
    # A formula pre-registrada divide a CONTAGEM de terminais pelo span entre o primeiro e o
    # ultimo — mas entre n eventos ha n-1 intervalos, entao ela superestima por 1/(n-1). O vies
    # nao e igual nos pontos (n encolhe quando N cresce), e ele favorece justamente o N alto,
    # isto e, a linearidade sob julgamento. A variante por intervalos vai publicada ao lado como
    # teste de sensibilidade; a manchete continua sendo a formula pre-registrada.
    vazao_intervalos="$(awk -v n="$regime_n" -v s="$regime_s" 'BEGIN{printf "%.4f", (s>0 && n>1)? (n-1)/s : 0}')"
    vazao_parede="$(awk -v n="$concluidos" -v s="$parede_s" 'BEGIN{printf "%.4f", (s>0)? n/s : 0}')"

    # --- telemetria agregada (item 7)
    # Sexto portao, acrescentado DEPOIS dos cinco pre-registrados e por isso declarado como
    # emenda: duas corridas do 026 foram corrompidas por o *host* suspender no meio (708 s e
    # 2695 s), e nenhum dos cinco pega isso — suspensao nao perde Video, nao falha Video e nao
    # reinicia container, so estica o denominador. A serie da telemetria e quem denuncia: com
    # amostragem de 5 s, qualquer intervalo grande entre amostras e ausencia do host.
    local max_gap
    max_gap="$(grep -oE '^[0-9]+ load=' "$saida/telemetria.txt" | cut -d' ' -f1 \
        | awk 'NR==1{p=$1;m=0} NR>1{d=$1-p; if(d>m)m=d; p=$1} END{print m+0}')"

    local cpu_extracao cpu_infra carga_media
    # `vistos[$1]++ == 0 && next` descarta a PRIMEIRA amostra de cada container: o primeiro
    # `docker stats --no-stream` reporta o acumulado desde o boot, nao um delta (1547% para o
    # Keycloak, medido no 026), e numa corrida curta uma amostra dessas domina a media.
    cpu_extracao="$(awk '/-extracao-/ {gsub(/%/,"",$2); if (vistos[$1]++ == 0) next; s+=$2; c++} END{printf "%.0f", (c)? s/c : 0}' "$saida/telemetria.txt")"
    cpu_infra="$(awk '/-(postgres|minio|videos|notificacao|keycloak|mailhog)-/ {gsub(/%/,"",$2); if (vistos[$1]++ == 0) next; s+=$2; c++} END{printf "%.0f", (c)? s/c : 0}' "$saida/telemetria.txt")"
    carga_media="$(awk -F'load=' '/load=/{s+=$2; c++} END{printf "%.1f", (c)? s/c : 0}' "$saida/telemetria.txt")"
    # Ocupacao do host: unica fonte independente de container. `cpu_infra` acima exclui o
    # RabbitMQ de proposito — a leitura dele por `docker stats` oscila entre 0,3% e 894% em
    # amostras de 2 s sem correlacao com trabalho (medido no 026) e nao e usavel.
    local host_ocupado
    host_ocupado="$(grep -oE 'host=[0-9_]+' "$saida/telemetria.txt" | sed 's/host=//' | tr '_' ' ' \
        | awk 'NR>1{t=0; for(i=1;i<=7;i++) t+=$i; dt=t-pt; di=($4+$5)-pi;
                    if(dt>0){s+=100*(1-di/dt); c++}}
               {pt=0; for(i=1;i<=7;i++) pt+=$i; pi=$4+$5}
               END{printf "%.0f", (c)? s/c : 0}')"

    # --- portoes de validade. Vazao e a metrica que premia trabalho mal feito: o 025 aprendeu
    # na marra que "terminal" nao basta, porque um ARQUIVO_INVALIDO rapido *acelera* a drenagem.
    # Aspas obrigatorias na gravacao: o valor carrega parenteses (`presos(25)`), e sem elas o
    # `source` do resumo morre com erro de sintaxe — o ponto invalido derrubava o relatorio
    # inteiro, que e o pior jeito de um ponto invalido se manifestar. Achado no 026.
    local invalido=''
    (( recusados > 0 ))       && invalido="${invalido}nao-202($recusados) "
    (( presos + ausentes > 0 )) && invalido="${invalido}presos($((presos+ausentes))) "
    (( falhados > 0 ))        && invalido="${invalido}FALHOU($falhados) "
    (( frames_errados > 0 ))  && invalido="${invalido}frames($frames_errados) "
    (( restarts > 0 ))        && invalido="${invalido}restart "
    (( max_gap > 30 ))        && invalido="${invalido}descontinuidade(${max_gap}s) "

    cat > "$saida/ponto.env" <<EOF
ROTULO=$rotulo
N=$n
REP=$rep
CPUS=$cpus
VIDEOS=$videos
ACEITOS=$aceitos
RECUSADOS=$recusados
INJECAO_S=$duracao_injecao
DRENAGEM_S=$drenagem
REGIME_N=$regime_n
REGIME_S=$regime_s
VAZAO_REGIME=$vazao_regime
VAZAO_INTERVALOS=$vazao_intervalos
PAREDE_S=$parede_s
VAZAO_PAREDE=$vazao_parede
CONCLUIDOS=$concluidos
FALHADOS=$falhados
PRESOS=$((presos + ausentes))
FRAMES_ERRADOS=$frames_errados
RESTARTS=$restarts
MAX_GAP_S=$max_gap
CPU_EXTRACAO=$cpu_extracao
CPU_INFRA=$cpu_infra
CARGA_MEDIA=$carga_media
HOST_OCUPADO=$host_ocupado
INVALIDO="${invalido:-nao}"
EOF

    echo
    echo "    vazao de regime .: $vazao_regime Video/s  ($regime_n terminais em ${regime_s}s pos-injecao)"
    echo "      por intervalos : $vazao_intervalos Video/s  (n-1 sobre o mesmo span; sensibilidade)"
    echo "    vazao de parede .: $vazao_parede Video/s  ($concluidos em ${parede_s}s)"
    echo "    CPU extracao ....: ${cpu_extracao}% por replica (media)   infra: ${cpu_infra}%   load: $carga_media"
    echo "    host ............: ${host_ocupado}% ocupado (dos 20 nucleos, via /proc/stat)"
    echo "    continuidade ....: maior intervalo entre amostras: ${max_gap}s"
    if [[ -z "$invalido" ]]; then
        ok "portoes: os cinco passam"
    else
        aviso "PONTO INVALIDO: $invalido"
    fi
    return 0
}

# ---------------------------------------------------------------------------------------
resumo() {
    passo "Resumo — eficiencia de escala"
    shopt -s nullglob
    local arquivos=( "$base"/*/ponto.env )
    (( ${#arquivos[@]} )) || falha "nenhum ponto medido"

    local f
    : > "$base/pontos.psv"
    for f in "${arquivos[@]}"; do
        ( # subshell: cada ponto.env sobrescreve as mesmas variaveis
          # shellcheck disable=SC1090
          source "$f"
          printf '%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
            "${ROTULO:-?}" "${N:-0}" "${VIDEOS:-0}" "${VAZAO_REGIME:-0}" "${VAZAO_INTERVALOS:-0}" \
            "${VAZAO_PAREDE:-0}" "${REGIME_N:-0}" "${CPU_EXTRACAO:-0}" "${CPU_INFRA:-0}" \
            "${CARGA_MEDIA:-0}" "${RESTARTS:-0}" "${INVALIDO:-?}"
        ) >> "$base/pontos.psv"
    done
    sort -t'|' -k2,2n -k1,1 -o "$base/pontos.psv" "$base/pontos.psv"

    printf '\n    %-26s %3s %7s %9s %9s %9s %7s %8s %8s %6s %s\n' \
        ROTULO N VIDEOS REGIME INTERV PAREDE JANELA CPU_EXT CPU_INF LOAD PORTOES
    local rotulo n videos vr vi vp rn ce ci load restarts inval
    while IFS='|' read -r rotulo n videos vr vi vp rn ce ci load restarts inval; do
        printf '    %-26s %3s %7s %9s %9s %9s %7s %7s%% %7s%% %6s %s\n' \
            "$rotulo" "$n" "$videos" "$vr" "$vi" "$vp" "$rn" "$ce" "$ci" "$load" "$inval"
    done < "$base/pontos.psv"

    # Mediana das repeticoes VALIDAS por N (item 6); so os pontos da varredura (rotulo `nX-rY`),
    # nunca os controles — o controle sujo mede outra coisa e entraria na curva como ruido.
    echo
    printf '    %-4s %10s %11s %11s %s\n' N MEDIANA EFIC EFIC_INT CURVA
    awk -F'|' '
        $12=="nao" && $1 ~ /^n[0-9]+-r/ { v[$2] = v[$2] " " $4; w[$2] = w[$2] " " $5 }
        END {
            for (n in v) { ns[++k] = n+0 }
            for (i=1;i<k;i++) for (j=i+1;j<=k;j++) if (ns[j]<ns[i]) { t=ns[i]; ns[i]=ns[j]; ns[j]=t }
            base = mediana(v[ns[1]]); base_i = mediana(w[ns[1]]);
            if (ns[1] != 1 || base <= 0) { print "    (sem N=1 valido: eficiencia nao calculavel)"; exit }
            for (i=1;i<=k;i++) {
                n = ns[i]; m = mediana(v[n]); ef = m/(n*base);
                efi = (base_i>0) ? mediana(w[n])/(n*base_i) : 0;
                barra = ""; largura = int(ef*40+0.5);
                for (b=0;b<largura;b++) barra = barra "#";
                printf "    %-4d %10.4f %11.2f %11.2f  %s%s\n", n, m, ef, efi, barra, (ef<0.8) ? "  <-- quebra 0,80" : "";
            }
        }
        function mediana(lista,   c,x,i,j,t) {
            c = split(lista, x, " ");
            for (i=1;i<c;i++) for (j=i+1;j<=c;j++) if (x[j]+0 < x[i]+0) { t=x[i]; x[i]=x[j]; x[j]=t }
            return (c%2) ? x[(c+1)/2]+0 : (x[c/2]+x[c/2+1])/2;
        }' "$base/pontos.psv"

    echo
    echo "    Criterio fixado antes de rodar: eficiencia >= 0,80. O experimento RELATA o"
    echo "    primeiro N em que ela quebra; o valor esta na curva inteira, nao no passa/reprova."
    echo "    Saida completa: scripts/carga/saida/escalabilidade/"
}

# ---------------------------------------------------------------------------------------
varredura() {
    exige_ferramentas
    calibra

    # Ordem randomizada (item 4): reforco do `down -v`, nao substituto. Se a maquina derivar
    # ao longo de ~2 h, a deriva se espalha pelos pontos em vez de virar inclinacao.
    local corridas=() n rep
    for n in 1 2 4 6; do for rep in 1 2; do corridas+=("$n $rep"); done; done
    local ordem; ordem="$(printf '%s\n' "${corridas[@]}" | shuf)"

    passo "Ordem randomizada da varredura"
    nl -w4 -s'  ' <<< "$ordem" | sed 's/^/    /'

    while read -r n rep; do
        ponto "$n" "$rep" || aviso "ponto N=$n rep=$rep abortado — recorrendo uma vez (item: portoes)"
        if [[ "$(grep -c '^INVALIDO=nao$' "$base/n$n-r$rep/ponto.env" 2>/dev/null || echo 0)" != 1 ]]; then
            aviso "recorrendo do ponto N=$n rep=$rep"
            ponto "$n" "$rep" || true
        fi
    done <<< "$ordem"

    # Item 6: dois controles finais em N=1.
    passo "Controles finais (item 6)"
    ponto 1 controle || true          # limpo: deriva de maquina
    ponto 1 controle sujo || true     # sujo: estado acumulado

    resumo
}

# ---------------------------------------------------------------------------------------
case "${1:-}" in
    calibra)   calibra ;;
    particao)  mkdir -p "$base/calibracao"; particao "$base/calibracao" ;;
    ponto)     exige_ferramentas; ponto "${2:?N}" "${3:-1}" "${4:-limpo}" ;;
    seco)      exige_ferramentas; calibra; ponto 1 1 ;;
    varredura) varredura ;;
    resumo)    resumo ;;
    *) echo "uso: $0 calibra|particao|ponto <N> <rep>|seco|varredura|resumo" >&2; exit 2 ;;
esac
