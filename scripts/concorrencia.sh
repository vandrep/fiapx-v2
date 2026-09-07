#!/usr/bin/env bash
# Observa, de fora, o primeiro requisito do enunciado: mais de um video processado ao mesmo
# tempo (ticket 049).
#
# Nao usa o overlay de carga nem o k6 — sobe a MESMA stack do README (`docker compose up -d`,
# onde o `extracao` tem duas replicas) e olha o sistema apenas pela borda publica: envia uma
# rajada autenticada e acompanha a listagem `GET /videos`. O que ele mede e o intervalo em que
# cada Video fica em PROCESSANDO, que e exatamente o intervalo da Extracao — o estado abre em
# `extracao.iniciada` e fecha em `extracao.concluida`. Dois intervalos que se sobrepoem no
# relogio sao duas Extracoes simultaneas, sem precisar espiar log de container.
#
# Criterio, fixado antes de rodar: pelo menos DOIS Videos em PROCESSANDO no mesmo instante e
# por pelo menos duas amostras seguidas (o porque da persistencia esta no passo 5), e todos os
# Videos da rajada terminando em CONCLUIDO, cada um com o seu Pacote intacto e invisivel para
# o outro usuario. Concorrencia que troca resultado nao e concorrencia.
#
# Uso:
#   scripts/concorrencia.sh              rajada de 8 Videos, deixa a stack de pe
#   scripts/concorrencia.sh 10           rajada de 10
#
# Variaveis:
#   FIAPX_CONCORRENCIA_VIDEO   arquivo .mp4 a enviar (default: o fixture de 3s do extracao).
#                              Um video mais longo alarga a janela de sobreposicao e torna a
#                              demonstracao mais confortavel de acompanhar na tela.
#   FIAPX_EXTRACAO_REPLICAS    quantas replicas do extracao subir (default: as 2 do arquivo).
#                              E a mesma variavel do docker-compose.yml e do overlay de carga:
#                              este script so roda `up -d`, e o Compose a interpola. Nome
#                              proprio para o video, e nao o FIAPX_FIXTURE dos scripts de
#                              carga, porque la o valor e um nome dentro de
#                              scripts/carga/fixtures e aqui e um caminho qualquer.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"

quantidade="${1:-8}"
fixture="${FIAPX_CONCORRENCIA_VIDEO:-extracao/src/test/resources/fixtures/video-valido.mp4}"

timeout_saude=180
timeout_rajada=300
# Amostragem da listagem. O fixture de 3s extrai em menos de um segundo com a stack quente:
# amostrar de segundo em segundo perderia a janela inteira, e a rajada de oito existe pela
# mesma razao — com poucos Videos a drenagem acaba antes de haver o que ver.
intervalo_amostra=0.1

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

# `date +%s%3N` e GNU; onde nao existir, a resolucao cai para segundos e a linha do tempo
# fica mais grossa, mas o julgamento continua valendo.
agora_ms() {
    local t; t="$(date +%s%3N)"
    [[ "$t" == *N* ]] && t="$(( $(date +%s) * 1000 ))"
    echo "$t"
}

# ---------------------------------------------------------------------------------------
passo "0. Dependencias do host"

for ferramenta in docker curl jq unzip; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done
[[ -f "$fixture" ]] || falha "video de envio nao encontrado: $fixture"
(( quantidade >= 2 )) || falha "a rajada precisa de pelo menos 2 Videos, veio $quantidade"
ok "docker, curl, jq, unzip; enviando $quantidade x $(basename "$fixture")"

# ---------------------------------------------------------------------------------------
passo "1. Compose de pe, com as replicas do extracao"

# `up -d` puro: o numero de replicas e `${FIAPX_EXTRACAO_REPLICAS:-2}` no proprio
# docker-compose.yml, entao a variavel do ambiente chega ao Compose sem este script precisar
# de `--scale`. Uma mecanica so para um conceito so.
docker compose up -d > "$trabalho/compose.log" 2>&1 \
    || { cat "$trabalho/compose.log" >&2; falha "docker compose up falhou"; }

inicio=$SECONDS
while :; do
    saude="$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -E '^(videos|extracao|notificacao) ' || true)"
    servicos="$(echo "$saude" | awk 'NF {print $1}' | sort -u | wc -l)"
    doentes="$(echo "$saude" | grep -cv ' healthy$' || true)"
    (( servicos == 3 && doentes == 0 )) && break
    (( SECONDS - inicio > timeout_saude )) && falha "servicos nao ficaram saudaveis em ${timeout_saude}s:"$'\n'"$saude"
    sleep 3
done

replicas="$(docker compose ps -q extracao | wc -l)"
docker compose ps --format '{{.Name}} {{.Health}}' | grep '^[^ ]*extracao' | sed 's/^/    /'
(( replicas >= 2 )) || falha "so ha $replicas replica de extracao — a demo precisa de 2 (docker-compose.yml, replicas: \${FIAPX_EXTRACAO_REPLICAS:-2})"
ok "$replicas replicas do extracao, competindo pela mesma fila extracao.extrair"

# ---------------------------------------------------------------------------------------
passo "2. Token do usuario demo"

token="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos \
    -d username=demo -d password=demo | jq -r '.access_token // empty' || true)"
[[ -n "$token" ]] || falha "Keycloak nao devolveu access_token para demo/demo"
autenticado=(-H "Authorization: Bearer $token")
ok "token obtido"

# ---------------------------------------------------------------------------------------
passo "3. Rajada de $quantidade envios"

# Em paralelo de proposito: envio sequencial ja daria concorrencia no `extracao` (a fila
# guarda o resto), mas a rajada e o que o requisito descreve.
t0="$(agora_ms)"
for (( i = 0; i < quantidade; i++ )); do
    curl -sS -o "$trabalho/envio-$i.json" -w '%{http_code}' -X POST "$videos_url/videos" \
        "${autenticado[@]}" -F "arquivo=@$fixture;type=video/mp4" > "$trabalho/codigo-$i.txt" &
done
wait

ids=()
for (( i = 0; i < quantidade; i++ )); do
    codigo="$(cat "$trabalho/codigo-$i.txt")"
    [[ "$codigo" == 202 ]] || falha "envio $i devolveu $codigo, esperava 202: $(cat "$trabalho/envio-$i.json")"
    ids+=("$(jq -r .id "$trabalho/envio-$i.json")")
done
ok "$quantidade x 202 Accepted em $(( $(agora_ms) - t0 ))ms"

# ---------------------------------------------------------------------------------------
passo "4. Linha do tempo do estado PROCESSANDO, lida da listagem"

# Uma amostra = um `GET /videos`. Para cada Video guardamos o primeiro instante em que ele
# aparece em PROCESSANDO e o instante em que ele sai para um estado terminal: esse par e o
# intervalo da Extracao vista de fora.
inicio_ms=(); fim_ms=(); estado_final=()
for (( i = 0; i < quantidade; i++ )); do inicio_ms+=(0); fim_ms+=(0); estado_final+=(""); done

# A listagem sai ordenada por recebidoEm decrescente, entao os Videos da rajada sao os
# primeiros — mas a pagina precisa caber a rajada inteira, ou os que sobrarem nunca sairiam de
# `pendentes` e o script reprovaria por timeout, culpando a stack por um limite dele proprio.
pagina_da_listagem=$(( quantidade > 100 ? quantidade : 100 ))

pico=0            # maior numero de Videos vistos em PROCESSANDO numa mesma amostra
sequencia=0       # amostras consecutivas com dois ou mais; ver o julgamento
sequencia_maxima=0
amostras=0
inicio=$SECONDS
while :; do
    instante="$(agora_ms)"
    curl -sS "$videos_url/videos?tamanho=$pagina_da_listagem" "${autenticado[@]}" \
        | jq -r '.conteudo[] | "\(.id) \(.estado)"' > "$trabalho/listagem.txt"
    amostras=$(( amostras + 1 ))

    simultaneos=0
    pendentes=0
    for (( i = 0; i < quantidade; i++ )); do
        estado="$(awk -v id="${ids[i]}" '$1 == id {print $2}' "$trabalho/listagem.txt")"
        case "$estado" in
            PROCESSANDO)
                (( inicio_ms[i] == 0 )) && inicio_ms[i]=$instante
                simultaneos=$(( simultaneos + 1 ))
                pendentes=$(( pendentes + 1 ))
                ;;
            CONCLUIDO|FALHOU)
                if [[ -z "${estado_final[i]}" ]]; then
                    estado_final[i]="$estado"
                    fim_ms[i]=$instante
                fi
                ;;
            *)  pendentes=$(( pendentes + 1 )) ;;
        esac
    done
    (( simultaneos > pico )) && pico=$simultaneos
    if (( simultaneos >= 2 )); then
        sequencia=$(( sequencia + 1 ))
        (( sequencia > sequencia_maxima )) && sequencia_maxima=$sequencia
    else
        sequencia=0
    fi

    (( pendentes == 0 )) && break
    (( SECONDS - inicio > timeout_rajada )) && falha "a rajada nao drenou em ${timeout_rajada}s"
    sleep "$intervalo_amostra"
done
ok "$amostras amostras da listagem ate a rajada drenar"

echo
echo "    ${negrito}Cada barra e um Video em PROCESSANDO, no eixo do tempo (1 coluna ~ 100ms)${normal}"
for (( i = 0; i < quantidade; i++ )); do
    if (( inicio_ms[i] == 0 )); then
        printf '    %s  %s\n' "${ids[i]:0:8}" "(PROCESSANDO nao amostrado)"
        continue
    fi
    coluna_inicio=$(( (inicio_ms[i] - t0) / 100 ))
    largura=$(( (fim_ms[i] - inicio_ms[i]) / 100 ))
    (( largura < 1 )) && largura=1
    # Quem formata a duracao e o awk, e o printf so a repassa como texto: os dois seguem o
    # locale, e cada um a seu modo — deixar o numero atravessar como string evita depender de
    # os dois concordarem sobre ponto ou virgula decimal.
    printf '    %s  %*s%s  %s (%ss)\n' \
        "${ids[i]:0:8}" "$coluna_inicio" "" \
        "$(printf '#%.0s' $(seq 1 $largura))" \
        "${estado_final[i]}" \
        "$(awk -v a="${inicio_ms[i]}" -v b="${fim_ms[i]}" 'BEGIN {printf "%.1f", (b-a)/1000}')"
done

# ---------------------------------------------------------------------------------------
passo "5. Julgamento"

# Duas condicoes, e a segunda existe por causa de uma corrida conhecida: `PROCESSANDO` abre
# quando o `videos` consome `extracao.iniciada` e fecha quando consome `extracao.concluida`,
# em canais diferentes e com prefetch 20 — nada garante a ordem entre o fim de um Video e o
# comeco do outro. Uma replica unica que termina A e ja pega B pode, num unico instante,
# aparecer com os dois em PROCESSANDO. Exigir que a sobreposicao PERSISTA por duas amostras
# (~200 ms) descarta esse artefato sem descartar concorrencia de verdade: com duas replicas,
# quatro rodadas seguidas sustentaram quatro das cinco amostras.
(( pico >= 2 )) || falha "nunca houve mais de $pico Video em PROCESSANDO ao mesmo tempo — a stack processou um de cada vez"
(( sequencia_maxima >= 2 )) || falha "houve $pico Videos em PROCESSANDO juntos, mas so num instante isolado — indistinguivel de reordenacao de evento"
ok "pico de $pico Videos em PROCESSANDO simultaneo, sustentado por $sequencia_maxima amostras (criterio: >= 2, por >= 2 amostras)"

for (( i = 0; i < quantidade; i++ )); do
    [[ "${estado_final[i]}" == CONCLUIDO ]] \
        || falha "Video ${ids[i]} terminou em ${estado_final[i]}, esperava CONCLUIDO"
done
ok "os $quantidade Videos da rajada chegaram a CONCLUIDO"

# Concorrencia que troca resultado nao serve: cada Pacote tem de ser um ZIP integro e com
# frame dentro. E o mesmo julgamento do smoke, agora sob disputa pelo scratch compartilhado.
for (( i = 0; i < quantidade; i++ )); do
    codigo="$(curl -sS -o "$trabalho/pacote-$i.zip" -w '%{http_code}' \
        "$videos_url/videos/${ids[i]}/pacote" "${autenticado[@]}" || true)"
    [[ "$codigo" == 200 ]] || falha "GET do Pacote de ${ids[i]} devolveu $codigo, esperava 200"
    unzip -tq "$trabalho/pacote-$i.zip" >/dev/null || falha "Pacote de ${ids[i]} corrompido"
    frames="$(unzip -Z1 "$trabalho/pacote-$i.zip" | grep -c '\.png$' || true)"
    (( frames > 0 )) || falha "Pacote de ${ids[i]} nao tem frame nenhum"
    printf '    %s  %s frames, %s bytes\n' "${ids[i]:0:8}" "$frames" "$(wc -c < "$trabalho/pacote-$i.zip")"
done
ok "todos os Pacotes integros e com frames"

token_outro="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos \
    -d username=outro -d password=outro | jq -r '.access_token // empty' || true)"
[[ -n "$token_outro" ]] || falha "Keycloak nao devolveu access_token para outro/outro"
for (( i = 0; i < quantidade; i++ )); do
    codigo="$(curl -sS -o /dev/null -w '%{http_code}' \
        "$videos_url/videos/${ids[i]}" -H "Authorization: Bearer $token_outro" || true)"
    [[ "$codigo" == 404 ]] || falha "Video ${ids[i]} respondeu $codigo para o outro usuario, esperava 404"
done
ok "nenhum dos $quantidade Videos vazou para o outro usuario — a rajada nao trocou dono"

echo
echo "${negrito}${verde}Concorrencia observada.${normal} Pico de $pico Extracoes simultaneas com $replicas replicas."
echo "    Para ver mais de perto:  docker compose logs -f extracao"
echo "    Para escalar:            FIAPX_EXTRACAO_REPLICAS=4 $0 16"
