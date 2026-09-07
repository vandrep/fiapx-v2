#!/usr/bin/env bash
# Caca ao travamento do ticket 061: N ciclos POST /videos -> CONCLUIDO contra o Compose, com
# teto por ciclo. No primeiro ciclo que estourar o teto, coleta o estado da stack e para.
#
# Por que este script existe, e por que ele nao e o smoke.sh
#
#   O defeito que ele persegue e raro (era da ordem de 1 Extracao a cada 15) e silencioso: a
#   mensagem fica sem ack, nenhuma thread trabalha, nenhuma linha e logada, e a replica fica
#   presa para sempre porque `max-outstanding-messages=1`. O `smoke.sh` manda um Video de cada
#   vez e passa; o `conservacao.sh` mede conservacao sob falha injetada, que e outro eixo. O
#   que acha este defeito e repeticao com teto — e, quando ele aparece, uma coleta imediata,
#   porque o unico momento em que a evidencia existe e enquanto a replica esta presa.
#
#   Ele reprova por AUSENCIA de progresso, nao por erro: um ciclo que passa do teto ja e o
#   defeito, e nao lentidao. O teto de 45 s e folga larga sobre os ~2 s de um ciclo do fixture.
#
# Quando rodar
#
#   Ao mexer no consumidor de `extracao.extrair`, nos adapters de I/O do `extracao` ou em
#   qualquer coisa que reagende trabalho entre threads (retentativa, offload, contexto Vert.x).
#   Foi ele que mediu o ticket 061: 4 travamentos em ~60 ciclos com a tolerancia a falhas por
#   interceptor, 0 em 90 depois de tira-la.
#
# Uso:
#   scripts/carga/travamento.sh                 10 ciclos, teto de 45 s, recriando a stack
#   CICLOS=25 TETO=60 scripts/carga/travamento.sh
#   RECRIA=0 scripts/carga/travamento.sh        usa a stack que ja esta de pe
#   OVERLAY=arquivo.yml scripts/carga/travamento.sh   soma um overlay ao docker-compose.yml
#
#   Uma corrida de mais de ~10 min no host de desenvolvimento precisa de
#   `systemd-inhibit --what=sleep:idle --why=...` na frente: a maquina suspende sozinha e o
#   teto por ciclo passaria a medir a ausencia do host.
#
# A coleta, em SAIDA (default: um diretorio novo em /tmp), tem o que responde "onde parou":
#   filas.txt / consumidores.txt  quem esta com a mensagem sem ack
#   scratch-<replica>.txt         ate onde a Extracao chegou em disco (diretorio da tentativa
#                                 vazio = parou antes do download; com frames = parou depois)
#   log-<replica>.txt             o log ate o momento, com o thread dump que o SIGQUIT gerou
#   video.json                    o estado do Video pela borda
set -uo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"

ciclos="${CICLOS:-10}"
teto="${TETO:-45}"
recria="${RECRIA:-1}"
saida="${SAIDA:-$(mktemp -d -t travamento-XXXXXX)}"
fixture="extracao/src/test/resources/fixtures/video-valido.mp4"

compose=(docker compose -f docker-compose.yml)
[[ -n "${OVERLAY:-}" ]] && compose+=(-f "$OVERLAY")

mkdir -p "$saida"
echo "coleta em $saida"

for ferramenta in docker curl jq; do
    command -v "$ferramenta" >/dev/null || { echo "$ferramenta nao esta no PATH" >&2; exit 2; }
done

# Recriar e parte do roteiro, e nao zelo: os travamentos medidos no ticket 061 apareceram nos
# primeiros ciclos depois de a replica subir, quando os canais de saida ainda estao abrindo.
if [[ "$recria" == 1 ]]; then
    echo "== force-recreate da stack"
    "${compose[@]}" up -d --force-recreate --remove-orphans > "$saida/up.log" 2>&1 \
        || { cat "$saida/up.log" >&2; exit 2; }
fi

echo "== esperando os tres servicos saudaveis"
inicio=$SECONDS
while :; do
    saude="$("${compose[@]}" ps --format '{{.Service}} {{.Health}}' | grep -E '^(videos|extracao|notificacao) ' || true)"
    servicos="$(echo "$saude" | awk 'NF {print $1}' | sort -u | wc -l)"
    doentes="$(echo "$saude" | grep -cv ' healthy$' || true)"
    (( servicos == 3 && doentes == 0 )) && break
    (( SECONDS - inicio > 300 )) && { echo "servicos nao ficaram saudaveis:"$'\n'"$saude" >&2; exit 2; }
    sleep 3
done

token="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos -d username=demo -d password=demo \
    | jq -r '.access_token // empty')"
[[ -n "$token" ]] || { echo "Keycloak nao devolveu access_token para demo/demo" >&2; exit 2; }

coleta() {
    local id="$1" motivo="$2"
    echo "== TRAVOU ($motivo), idVideo=$id; coletando em $saida"
    echo "$id" > "$saida/id-travado.txt"
    "${compose[@]}" exec -T rabbitmq rabbitmqctl list_queues name messages messages_unacknowledged consumers \
        > "$saida/filas.txt" 2>&1
    "${compose[@]}" exec -T rabbitmq rabbitmqctl list_consumers > "$saida/consumidores.txt" 2>&1
    # A imagem do servico e JRE: nao ha jcmd nem jstack dentro dela. SIGQUIT no PID 1 manda o
    # thread dump para o proprio stdout, que e de onde `docker logs` o tira logo abaixo.
    local containers
    containers="$("${compose[@]}" ps -q extracao)"
    for c in $containers; do
        nome="$(docker inspect -f '{{.Name}}' "$c" | tr -d /)"
        docker exec "$c" sh -c 'ls -laR /var/fiapx/extracao' > "$saida/scratch-$nome.txt" 2>&1
        docker exec "$c" kill -3 1
    done
    sleep 3
    for c in $containers; do
        nome="$(docker inspect -f '{{.Name}}' "$c" | tr -d /)"
        docker logs "$c" > "$saida/log-$nome.txt" 2>&1
    done
    curl -sS "$videos_url/videos/$id" -H "Authorization: Bearer $token" > "$saida/video.json"
}

for (( i = 1; i <= ciclos; i++ )); do
    envio="$(curl -sS -X POST "$videos_url/videos" -H "Authorization: Bearer $token" \
        -F "arquivo=@$fixture;type=video/mp4")"
    id="$(jq -r '.id // empty' <<< "$envio")"
    [[ -n "$id" ]] || { echo "ciclo $i: POST /videos falhou: $envio" >&2; exit 2; }

    t0=$SECONDS
    while :; do
        estado="$(curl -sS "$videos_url/videos/$id" -H "Authorization: Bearer $token" | jq -r .estado)"
        [[ "$estado" == CONCLUIDO ]] && break
        if [[ "$estado" == FALHOU ]]; then
            echo "ciclo $i: Video $id foi a FALHOU, e o fixture e valido" >&2
            coleta "$id" "FALHOU inesperado"
            exit 1
        fi
        if (( SECONDS - t0 > teto )); then
            coleta "$id" "teto de ${teto}s em $estado"
            exit 1
        fi
        sleep 1
    done
    echo "ciclo $i/$ciclos concluido em $((SECONDS - t0))s ($id)"
done

echo "== $ciclos ciclos sem travamento"
