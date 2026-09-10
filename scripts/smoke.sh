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
#
# Os passos 10 e 11 sao do ticket 059 e julgam a camada de observabilidade. Eles nao sao
# decoracao: o 10 e a UNICA coisa no repositorio que exercita a correlacao ponta a ponta —
# nenhum @QuarkusTest publica mensagem entre servicos, e a suite roda com o SDK desligado —, e
# o 11 e o que impede a observabilidade de virar, ela propria, uma causa de indisponibilidade.
#
# O passo 12 e do ticket 092 e julga o PAINEL: ele le as queries do arquivo versionado e
# reprova a que devolver serie vazia num sistema que acabou de processar um Video. E o preco
# que a reversao da recusa de painel curado paga ao argumento que dela sobrou de pe — um painel
# e a parte que envelhece primeiro, e envelhecer, aqui, e mostrar "No data" e nao quebrar nada.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$raiz"

videos_url="${FIAPX_VIDEOS_URL:-http://localhost:8080}"
keycloak_url="${FIAPX_KEYCLOAK_URL:-http://localhost:8081}"
mailhog_url="${FIAPX_MAILHOG_URL:-http://localhost:8025}"
grafana_url="${FIAPX_GRAFANA_URL:-http://localhost:3000}"

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
#
# Conta servicos distintos, e nao linhas: o `extracao` sobe com duas replicas desde o ticket
# 049, entao `docker compose ps` traz quatro linhas para tres servicos — e mais, se alguem
# usar `--scale`. O criterio continua o mesmo: os tres servicos presentes e nenhum container
# fora de `healthy`.
inicio=$SECONDS
while :; do
    saude="$(docker compose ps --format '{{.Service}} {{.Health}}' | grep -E '^(videos|extracao|notificacao) ' || true)"
    servicos="$(echo "$saude" | awk 'NF {print $1}' | sort -u | wc -l)"
    doentes="$(echo "$saude" | grep -cv ' healthy$' || true)"
    (( servicos == 3 && doentes == 0 )) && break
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
passo "10. O rastro do Vídeo, correlacionado por idVideo pelos três serviços"

# O Video que FALHOU, e nao o que concluiu, de proposito: e o unico cujo caminho passa pelos
# TRES servicos — `videos` recebe, `extracao` prova que nao e video, `videos` transiciona e
# anuncia, `notificacao` manda o e-mail. Achar os tres nomes num unico trace e o que prova que
# o contexto atravessou o RabbitMQ nos quatro saltos, por header AMQP.
#
# O Tempo nao publica porta para fora (ticket 058): quem enxerga a stack por dentro e o
# Grafana, entao a consulta vai pelo proxy de datasource dele. O uid e descoberto, nao fixado —
# ele e gerado pelo provisionamento da imagem.
tempo_uid="$(curl -sS "$grafana_url/api/datasources" | jq -r '.[] | select(.type=="tempo") | .uid' | head -1)"
[[ -n "$tempo_uid" ]] || falha "nenhum datasource do tipo tempo no Grafana em $grafana_url"
tempo="$grafana_url/api/datasources/proxy/uid/$tempo_uid"

# Laco de retentativa porque a exportacao e assincrona em dois estagios: o processador em lote
# do SDK segura os spans por alguns segundos, e o Tempo ainda leva mais alguns ate o trace
# ficar buscavel. Nao ha o que sincronizar aqui — so esperar.
inicio=$SECONDS
id_trace=""
while :; do
    # O filtro por servico nao e enfeite: `marcarVideo` poe o idVideo tambem no span de cada
    # GET de acompanhamento, entao a busca so pelo atributo casa dezenas de traces de uma linha
    # — e o primeiro que voltasse seria uma consulta, nao a travessia. Ancorar no `fiapx-extracao`
    # seleciona o unico trace em que o worker tocou este Video, que e a travessia inteira.
    id_trace="$(curl -sS -G "$tempo/api/search" \
        --data-urlencode "q={ .idVideo = \"$id_falha\" && resource.service.name = \"fiapx-extracao\" }" \
        --data-urlencode "start=$(( $(date +%s) - 3600 ))" \
        --data-urlencode "end=$(date +%s)" \
        --data-urlencode "limit=20" \
        | jq -r '.traces[0].traceID // empty' || true)"
    [[ -n "$id_trace" ]] && break
    (( SECONDS - inicio > 90 )) \
        && falha "nenhum trace do fiapx-extracao com idVideo=$id_falha no Tempo em 90s"
    printf '    ... aguardando a exportação\n'
    sleep 5
done
ok "trace $id_trace encontrado buscando por idVideo=$id_falha"

curl -sS "$tempo/api/traces/$id_trace" > "$trabalho/trace.json"
# Le o service.name de dentro dos atributos de recurso, em qualquer profundidade: a forma do
# OTLP-JSON muda entre versoes do Tempo, o nome do atributo nao.
servicos_no_trace="$(jq -r '[.. | objects | select(.key? == "service.name") | .value.stringValue]
    | unique | .[]' "$trabalho/trace.json")"
echo "$servicos_no_trace" | sed 's/^/    /'
for servico in fiapx-videos fiapx-extracao fiapx-notificacao; do
    grep -qx "$servico" <<< "$servicos_no_trace" \
        || falha "$servico não aparece no trace $id_trace; a travessia se partiu antes dele"
done
ok "os três serviços num único trace — o contexto atravessou o RabbitMQ por header AMQP"

# O Vídeo que falhou prova a largura da travessia; ele não prova a profundidade. Quem carrega
# `extracao.frames` — o span do `ffmpeg`, o único trecho que roda fora do JVM e o mesmo
# intervalo que a métrica `fiapx.extracao.duracao` cronometra — é o Vídeo que CONCLUIU, porque
# o inválido morre no ffprobe antes de extrair frame nenhum.
#
# Dois spansets ligados por `&&`, e não duas condições dentro de um: o span do `ffmpeg` **não**
# carrega `idVideo` de propósito — o adapter não conhece o Vídeo, e não deve. A forma de um
# spanset só exigiria os dois atributos no MESMO span e não casaria nunca, que foi como esta
# verificação reprovou ao ser escrita.
inicio=$SECONDS
while :; do
    trace_feliz="$(curl -sS -G "$tempo/api/search" \
        --data-urlencode "q={ .idVideo = \"$id\" } && { name = \"extracao.frames\" }" \
        --data-urlencode "start=$(( $(date +%s) - 3600 ))" \
        --data-urlencode "end=$(date +%s)" \
        --data-urlencode "limit=1" \
        | jq -r '.traces[0].traceID // empty' || true)"
    [[ -n "$trace_feliz" ]] && break
    (( SECONDS - inicio > 90 )) \
        && falha "nenhum span extracao.frames com idVideo=$id no Tempo em 90s"
    printf '    ... aguardando a exportação\n'
    sleep 5
done
ok "trace $trace_feliz traz o span do ffmpeg do Vídeo concluído"

# ---------------------------------------------------------------------------------------
passo "11. O ciclo do Vídeo sobrevive à observabilidade morta"

# A propriedade que o ticket 058 desenhou e o 059 nao pode ter quebrado ao ligar a exportacao:
# nenhum servico de negocio depende da stack para bootar, processar ou responder. Com ela
# parada os tres exportadores passam a falhar — e o Video tem de completar o ciclo mesmo assim.
docker compose stop observabilidade > "$trabalho/stop-obs.log" 2>&1 \
    || { cat "$trabalho/stop-obs.log" >&2; falha "não consegui parar a observabilidade"; }
# `start` de volta acontece de todo jeito, inclusive se um passo abaixo falhar: deixar a stack
# pela metade transformaria uma reprovacao deste script em duas.
religa_observabilidade() { docker compose start observabilidade > "$trabalho/start-obs.log" 2>&1 || true; }
trap 'religa_observabilidade; rm -rf "$trabalho"; $derruba && docker compose down' EXIT
ok "observabilidade parada"

codigo="$(curl -sS -o "$trabalho/envio-sem-obs.json" -w '%{http_code}' -X POST "$videos_url/videos" \
    "${autenticado[@]}" -F "arquivo=@$fixture_valido;type=video/mp4" || true)"
[[ "$codigo" == 202 ]] || falha "POST /videos com a observabilidade morta devolveu $codigo, esperava 202"
id_sem_obs="$(jq -r .id "$trabalho/envio-sem-obs.json")"
espera_estado "$id_sem_obs" CONCLUIDO
ok "Video $id_sem_obs chegou a CONCLUIDO sem stack de observabilidade nenhuma"

religa_observabilidade
ok "observabilidade de volta"

# ---------------------------------------------------------------------------------------
passo "12. Nenhuma query do painel curado devolve série vazia"

# O passo que impede o painel de envelhecer (ticket 092). O ADR 0004 recusou painel curado com
# dois argumentos, e um deles continua de pe depois da reversao: um painel e a parte que
# envelhece primeiro. Uma query que deixou de casar serie nenhuma — porque a metrica mudou de
# nome, porque o rotulo sumiu, porque a fila foi renomeada — nao quebra nada; ela so mostra
# "No data" sobre um sistema saudavel, que e exatamente a mentira que o ticket 091 achou nos
# tres dashboards de fabrica. Este passo transforma esse silencio em reprovacao.
#
# Ele le as queries DO ARQUIVO, e nao de uma copia aqui — inclusive o `allValue` da variavel de
# servico: duas verdades sobre a mesma pergunta e como as duas comecam a divergir. Editar o
# painel muda o que este passo cobra, de graca.
#
# E ele roda DEPOIS do ciclo do Video, e nao antes, porque `fiapx.extracao.duracao` esta
# legitimamente vazia ate a primeira Extracao — 88 nomes de metrica na base, zero com `durac`,
# medido numa stack recem-subida. Um passo posto cedo demais reprovaria um sistema saudavel.
#
# UM painel escapa deste passo, e escapa de propósito: o `count(...) or vector(0)` das filas sem
# consumidor nunca volta vazio, porque sem o `or vector(0)` ele diria "No data" justamente com o
# sistema saudavel. O que sobraria descoberto sao as duas metricas que ele usa, e as duas estao
# cobertas cruas por outros dois paineis (`..._messages_ready` e `..._consumers`): renomeada
# qualquer uma no upgrade do broker, quem reprova sao eles.

painel="docker/observabilidade/painel-infraestrutura.json"
[[ -f "$painel" ]] || falha "$painel nao existe"

# O passo 11 acabou de reiniciar o container; o Grafana leva alguns segundos ate responder.
inicio=$SECONDS
until curl -sf "$grafana_url/api/health" > /dev/null 2>&1; do
    (( SECONDS - inicio > 120 )) && falha "Grafana não voltou 120s depois do religa do passo 11"
    printf '    ... aguardando o Grafana voltar\n'
    sleep 3
done

# O painel e a home: abrir localhost:3000 cai nele sem navegar. Sem isso ele e mais um item
# numa lista de quatro, e o avaliador abre o RED por engano.
home="$(curl -sS "$grafana_url/api/dashboards/home" | jq -r '.redirectUri // empty' || true)"
[[ "$home" == *infraestrutura* ]] \
    || falha "a home do Grafana é '$home', e não o painel de infraestrutura"
ok "a home do Grafana é o painel: $home"

# Os tres uids de datasource que o painel fixa precisam existir de verdade — se a imagem
# renomear um deles no upgrade, todo painel que o usa vira "Datasource not found", e o erro
# aparece na tela em vez de aqui.
for uid in prometheus loki tempo; do
    curl -sf "$grafana_url/api/datasources/uid/$uid" > /dev/null \
        || falha "o painel aponta para o datasource '$uid', que não existe neste Grafana"
done
ok "os três datasources do painel existem: prometheus, loki, tempo"

# As variaveis do painel nao chegam ate aqui resolvidas — resolve-las e o trabalho do browser.
# `$servico` vira o `allValue` LIDO DO ARQUIVO, e `$idVideo` vira o Video que CONCLUIU: e o
# unico que tem span do ffmpeg, que e o que o segundo spanset da busca exige. Variavel nova no
# painel sem tratamento aqui nao passa em silencio — a guarda logo abaixo reprova.
servico_all="$(jq -r '.templating.list[] | select(.name == "servico") | .allValue' "$painel")"
[[ -n "$servico_all" && "$servico_all" != null ]] \
    || falha "a variável 'servico' do painel não tem allValue; o passo não sabe resolvê-la"

resolve_variaveis() {
    sed -e "s/\\\$servico/$servico_all/g" -e "s/\\\$idVideo/$id/g"
}

agora=$(date +%s)
desde=$(( agora - 3600 ))
consultadas=0

# `.panels[].targets[]` e nao uma travessia recursiva: o painel e plano de proposito, sem row
# colapsavel, e uma travessia recursiva esconderia o dia em que alguem aninhar um painel novo.
#
# `join` num separador de unidade, e nao `@tsv`: o `@tsv` do jq escapa a contrabarra, e a
# expressao da DLQ tem uma (`queue=~".+\\.dlq"`) — com ela dobrada, a query nao casa fila
# nenhuma, e o passo reprovaria um painel correto.
consultas="$(jq -r '.panels[] | .title as $t | .targets[]
    | [$t, .refId, .datasource.uid, (.expr // .query)] | join("\u001f")' "$painel")"
[[ -n "$consultas" ]] || falha "nenhuma query lida de $painel"

while IFS=$'\037' read -r titulo refid uid consulta; do
    consulta="$(resolve_variaveis <<< "$consulta")"
    consultadas=$(( consultadas + 1 ))

    # Variavel do Grafana que ninguem resolveu chegaria ao datasource como literal e casaria
    # zero — o passo reprovaria o painel por um defeito DELE PROPRIO. Melhor dizer qual e.
    [[ "$consulta" != *'$'* ]] \
        || falha "painel '$titulo' ($refid) usa variável que este passo não sabe resolver: $consulta"

    case "$uid" in
        prometheus)
            # `query_range`, e nao `query` instantanea: e assim que o painel consulta, e uma
            # janela cobre o instante da Extracao mesmo que ela tenha sido ha mais de 5 min.
            # Conta AMOSTRA nao-NaN, e nao serie: uma serie so de NaN volta como resultado
            # nao-vazio, e foi assim que a primeira versao deste passo aprovou um quantil que
            # nao desenhava nada.
            amostras="$(curl -sS -G "$grafana_url/api/datasources/proxy/uid/prometheus/api/v1/query_range" \
                --data-urlencode "query=$consulta" \
                --data-urlencode "start=$desde" --data-urlencode "end=$agora" \
                --data-urlencode "step=15" \
                | jq '[.data.result[]?.values[]? | select(.[1] != "NaN")] | length' || true)"
            ;;
        loki)
            amostras="$(curl -sS -G "$grafana_url/api/datasources/proxy/uid/loki/loki/api/v1/query_range" \
                --data-urlencode "query=$consulta" \
                --data-urlencode "start=${desde}000000000" --data-urlencode "end=${agora}000000000" \
                --data-urlencode "limit=5" \
                | jq '[.data.result[]?.values[]?] | length' || true)"
            ;;
        tempo)
            amostras="$(curl -sS -G "$grafana_url/api/datasources/proxy/uid/tempo/api/search" \
                --data-urlencode "q=$consulta" \
                --data-urlencode "start=$desde" --data-urlencode "end=$agora" \
                --data-urlencode "limit=20" \
                | jq '[.traces[]?] | length' || true)"
            ;;
        *)
            falha "painel '$titulo' usa datasource desconhecido '$uid'"
            ;;
    esac

    # Nao-numerico e datasource fora do ar ou resposta que o jq nao entendeu, e o `-gt` de um
    # nao-numero derrubaria o script sem dizer por que. As duas saidas sao reprovacao, e sao
    # defeitos diferentes.
    [[ "$amostras" =~ ^[0-9]+$ ]] \
        || falha "painel '$titulo' ($refid): o datasource '$uid' não respondeu um número consultável"
    (( amostras > 0 )) \
        || falha "painel '$titulo' ($refid) não devolveu nada num sistema que acabou de processar um Vídeo: $consulta"
    printf '    %6s amostras  %s\n' "$amostras" "$titulo [$refid]"
done <<< "$consultas"

ok "$consultadas queries do painel, todas com série"

# ---------------------------------------------------------------------------------------
echo
echo "${negrito}${verde}Smoke completo.${normal} Video concluido: $id | Video falho: $id_falha"
echo "    Swagger UI:  $videos_url/q/swagger-ui"
echo "    MailHog:     $mailhog_url"
echo "    Grafana:     $grafana_url  (o painel de infraestrutura e a home)"
$derruba || echo "    A stack continua de pe. Para encerrar: docker compose down"
