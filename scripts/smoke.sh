#!/usr/bin/env bash
# Verificacao ponta-a-ponta do FIAP X contra o Compose de verdade — e, na mesma peca, o
# roteiro da demo para a banca (ticket 022).
#
# Sao as duas coisas de proposito. O mapa decidiu que o fluxo completo nao vai para o CI
# (Compose inteiro num runner e fonte de flakiness que nao acrescenta nota), entao a unica
# prova de que os cinco servicos conversam e esta execucao — e uma prova que ninguem roda
# porque e chata de seguir nao prova nada. Por isso cada passo narra o que esta fazendo e
# mostra a resposta: o mesmo comando serve para o avaliador acompanhar e para o
# desenvolvedor descobrir que quebrou.
#
# Todo passo e verificado, nao so executado: estado errado, status HTTP errado ou ZIP
# corrompido derrubam o script no ponto exato.
#
# Uso:
#   scripts/smoke.sh              sobe o Compose se preciso, roda tudo, deixa a stack de pe
#   scripts/smoke.sh --derruba    idem, mas encerra com `docker compose down` no final
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
mailhog_url="${FIAPX_MAILHOG_URL:-http://localhost:8025}"

fixture_valido="extracao/src/test/resources/fixtures/video-valido.mp4"
fixture_invalido="extracao/src/test/resources/fixtures/arquivo-invalido.txt"

# Espera maxima pelo processamento de um Video. O fixture tem 3s e conclui em ~3s; a folga
# e para o primeiro `up`, quando as JVMs ainda estao aquecendo.
timeout_estado=120
timeout_saude=180

derruba=false
[[ "${1:-}" == "--derruba" ]] && derruba=true

trabalho="$(mktemp -d)"
trap 'rm -rf "$trabalho"; $derruba && docker compose down' EXIT

if [[ -t 1 ]]; then
    negrito=$'\e[1m'; verde=$'\e[32m'; vermelho=$'\e[31m'; normal=$'\e[0m'
else
    negrito=''; verde=''; vermelho=''; normal=''
fi

passo() { echo; echo "${negrito}==> $*${normal}"; }
ok()    { echo "    ${verde}OK${normal}  $*"; }
falha() { echo "    ${vermelho}FALHOU${normal}  $*" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
passo "0. Dependencias do host"

for ferramenta in docker curl jq unzip; do
    command -v "$ferramenta" >/dev/null || falha "$ferramenta nao esta no PATH"
done
ok "docker, curl, jq, unzip"

# ---------------------------------------------------------------------------------------
passo "1. Compose de pe"

# `up -d` e idempotente: com a stack ja rodando ele nao recria nada, entao o script pode ser
# repetido sem `down` no meio. A saida vai para arquivo e so aparece se algo der errado —
# sao ~25 linhas de "Container ... Running" que enterrariam o resto da narracao.
docker compose up -d > "$trabalho/compose.log" 2>&1 \
    || { cat "$trabalho/compose.log" >&2; falha "docker compose up falhou"; }
ok "docker compose up -d"

# Nao da para usar `docker compose up --wait`: o `minio-seed` e one-shot e sai com 0, o que
# o `--wait` trata como servico morto. A saude dos tres de negocio ja implica a de todo o
# resto — eles so sobem depois do `depends_on: service_healthy`.
inicio=$SECONDS
while :; do
    saude="$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -E '^(videos|extracao|notificacao) ' || true)"
    doentes="$(echo "$saude" | grep -cv ' healthy$' || true)"
    [[ "$(echo "$saude" | wc -l)" == 3 && "$doentes" == 0 ]] && break
    (( SECONDS - inicio > timeout_saude )) && falha "servicos nao ficaram saudaveis em ${timeout_saude}s:"$'\n'"$saude"
    sleep 3
done
ok "videos, extracao e notificacao saudaveis"

# ---------------------------------------------------------------------------------------
passo "2. Token do usuario demo no Keycloak"

token="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos \
    -d username=demo -d password=demo | jq -r '.access_token // empty' || true)"
[[ -n "$token" ]] || falha "Keycloak nao devolveu access_token para demo/demo"
ok "token obtido (${#token} caracteres)"

autenticado=(-H "Authorization: Bearer $token")

# Sem token a borda tem de recusar — se este passo passar com 401 ausente, o OIDC caiu e
# todo o resto do script estaria medindo uma API aberta.
sem_token="$(curl -sS -o /dev/null -w '%{http_code}' "$videos_url/videos" || true)"
[[ "$sem_token" == 401 ]] || falha "GET /videos sem token devolveu $sem_token, esperava 401"
ok "GET /videos sem token responde 401"

# ---------------------------------------------------------------------------------------
passo "3. Envio do video (caminho feliz)"

# O `;type=video/mp4` nao e enfeite: sem ele o curl manda application/octet-stream e a borda
# responde 415 (ticket 021). E o segundo argumento do -F, apos o `;`, e o que o avaliador
# mais esquece de copiar.
codigo="$(curl -sS -o "$trabalho/envio.json" -w '%{http_code}' -X POST "$videos_url/videos" \
    "${autenticado[@]}" -F "arquivo=@$fixture_valido;type=video/mp4" || true)"
[[ "$codigo" == 202 ]] || falha "POST /videos devolveu $codigo, esperava 202: $(cat "$trabalho/envio.json")"

id="$(jq -r .id "$trabalho/envio.json")"
estado_inicial="$(jq -r .estado "$trabalho/envio.json")"
[[ "$estado_inicial" == RECEBIDO ]] || falha "Video nasceu em $estado_inicial, esperava RECEBIDO"
jq -c . "$trabalho/envio.json" | sed 's/^/    /'
ok "202 Accepted, Video $id em RECEBIDO"

# ---------------------------------------------------------------------------------------
passo "4. Processamento assincrono ate CONCLUIDO"

# `espera_estado <id> <estado alvo>` — devolve o corpo do GET no estado alvo, ou morre.
# Qualquer um dos dois terminais encerra a espera: parar em FALHOU e o que transforma
# "esperei 2 minutos por nada" em "falhou com este motivo".
espera_estado() {
    local id_video="$1" alvo="$2" inicio_espera=$SECONDS estado
    while :; do
        curl -sS "$videos_url/videos/$id_video" "${autenticado[@]}" > "$trabalho/estado.json"
        estado="$(jq -r .estado "$trabalho/estado.json")"
        [[ "$estado" == "$alvo" ]] && return 0
        if [[ "$estado" == CONCLUIDO || "$estado" == FALHOU ]]; then
            falha "Video $id_video parou em $estado, esperava $alvo: $(jq -c . "$trabalho/estado.json")"
        fi
        (( SECONDS - inicio_espera > timeout_estado )) \
            && falha "Video $id_video ficou em $estado por mais de ${timeout_estado}s, esperava $alvo"
        printf '    ... %s\n' "$estado"
        sleep 2
    done
}

espera_estado "$id" CONCLUIDO
jq -c . "$trabalho/estado.json" | sed 's/^/    /'
ok "RECEBIDO -> PROCESSANDO -> CONCLUIDO"

# ---------------------------------------------------------------------------------------
passo "5. Download e validacao do Pacote"

codigo="$(curl -sS -D "$trabalho/pacote.headers" -o "$trabalho/pacote.zip" -w '%{http_code}' \
    "$videos_url/videos/$id/pacote" "${autenticado[@]}" || true)"
[[ "$codigo" == 200 ]] || falha "GET /videos/$id/pacote devolveu $codigo, esperava 200"

grep -qi '^content-type: application/zip' "$trabalho/pacote.headers" \
    || falha "Content-Type nao e application/zip: $(grep -i '^content-type' "$trabalho/pacote.headers")"
grep -qi '^content-disposition: attachment' "$trabalho/pacote.headers" \
    || falha "faltou Content-Disposition: attachment"

# `unzip -t` prova que o stream chegou inteiro. Sem ele um ZIP truncado pela conexao passaria
# batido — e streaming e exatamente onde isso aconteceria.
unzip -tq "$trabalho/pacote.zip" >/dev/null || falha "ZIP corrompido"

frames="$(unzip -Z1 "$trabalho/pacote.zip" | grep -c '\.png$' || true)"
(( frames > 0 )) || falha "Pacote nao tem nenhum frame .png"
unzip -Z1 "$trabalho/pacote.zip" | sed 's/^/    /'
ok "$frames frames, ZIP integro ($(wc -c < "$trabalho/pacote.zip") bytes)"

# ---------------------------------------------------------------------------------------
passo "6. Caminho de falha: arquivo que nao e video"

# Fixture versionado em vez de `head -c 2000 /dev/urandom` (o comando do README): a demo
# tem de dar o mesmo motivo toda vez que rodar. A extensao mentirosa e o ponto — a borda
# valida de forma declarativa, e quem prova que nao e video e o ffmpeg no extracao.
cp "$fixture_invalido" "$trabalho/quebrado.mp4"

codigo="$(curl -sS -o "$trabalho/envio-falha.json" -w '%{http_code}' -X POST "$videos_url/videos" \
    "${autenticado[@]}" -F "arquivo=@$trabalho/quebrado.mp4;type=video/mp4" || true)"
[[ "$codigo" == 202 ]] || falha "POST /videos do arquivo invalido devolveu $codigo, esperava 202"

id_falha="$(jq -r .id "$trabalho/envio-falha.json")"
ok "202 Accepted, Video $id_falha em RECEBIDO (a borda aceita — a prova e do extracao)"

espera_estado "$id_falha" FALHOU
motivo="$(jq -r .motivo "$trabalho/estado.json")"
[[ "$motivo" == ARQUIVO_INVALIDO ]] || falha "motivo foi $motivo, esperava ARQUIVO_INVALIDO"
jq -c . "$trabalho/estado.json" | sed 's/^/    /'
ok "FALHOU com motivo ARQUIVO_INVALIDO"

# ---------------------------------------------------------------------------------------
passo "7. Pacote de um Video que falhou"

codigo="$(curl -sS -o "$trabalho/conflito.json" -w '%{http_code}' \
    "$videos_url/videos/$id_falha/pacote" "${autenticado[@]}" || true)"
[[ "$codigo" == 409 ]] || falha "GET pacote de Video em FALHOU devolveu $codigo, esperava 409"
jq -c . "$trabalho/conflito.json" | sed 's/^/    /'
ok "409 Conflict em problem+json (ainda nao; 410 seria nao mais)"

# ---------------------------------------------------------------------------------------
passo "8. E-mail de falha no MailHog"

# Procura o e-mail *deste* Video, nao "existe algum e-mail": a caixa do MailHog sobrevive
# entre execucoes, entao contar mensagens faria a segunda rodada passar com o e-mail da
# primeira. A referencia no corpo e o idVideo (NotificacaoDeFalha).
#
# O gsub tira as quebras leves de quoted-printable — o corpo tem acentos, entao vem
# codificado e uma linha de 76 caracteres pode partir o UUID ao meio.
inicio=$SECONDS
while :; do
    encontrados="$(curl -sS "$mailhog_url/api/v2/messages?limit=200" \
        | jq --arg id "$id_falha" '[.items[] | select((.Content.Body | gsub("=\r\n"; "")) | contains($id))]')"
    (( $(jq length <<< "$encontrados") > 0 )) && break
    (( SECONDS - inicio > 60 )) && falha "nenhum e-mail no MailHog citando $id_falha em 60s"
    sleep 2
done

jq -r '.[0] | "    para: \(.Raw.To[0])"' <<< "$encontrados"
# O assunto vem MIME-encoded (RFC 2047) por causa dos acentos; nao vale a pena decodificar
# aqui, o que importa e que ele existe e cita o arquivo.
jq -r '.[0] | "    assunto (RFC 2047): \(.Content.Headers.Subject[0])"' <<< "$encontrados"
ok "e-mail entregue citando a referencia $id_falha"

# ---------------------------------------------------------------------------------------
passo "9. Video de um usuario nao aparece para outro"

token_outro="$(curl -sS -X POST "$keycloak_url/realms/fiapx/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=fiapx-videos \
    -d username=outro -d password=outro | jq -r '.access_token // empty' || true)"
[[ -n "$token_outro" ]] || falha "Keycloak nao devolveu access_token para outro/outro"

codigo="$(curl -sS -o "$trabalho/alheio.json" -w '%{http_code}' \
    "$videos_url/videos/$id" -H "Authorization: Bearer $token_outro" || true)"
[[ "$codigo" == 404 ]] || falha "GET do Video alheio devolveu $codigo, esperava 404"
ok "404 para o dono errado — o mesmo 404 de id inexistente, sem vazar a existencia"

# ---------------------------------------------------------------------------------------
echo
echo "${negrito}${verde}Smoke completo.${normal} Video concluido: $id | Video falho: $id_falha"
echo "    Swagger UI:  $videos_url/q/swagger-ui"
echo "    MailHog:     $mailhog_url"
$derruba || echo "    A stack continua de pe. Para encerrar: docker compose down"
