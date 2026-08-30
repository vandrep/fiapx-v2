#!/bin/sh
# Proxy L7 na frente de N replicas do `videos` (ticket 028). So existe neste overlay de carga —
# o `docker-compose.yml` da demo continua com uma replica so, sem proxy nenhum.
#
# Gera o nginx.conf aqui dentro, e nao como arquivo estatico no repo, porque o numero de
# replicas (FIAPX_VIDEOS_REPLICAS) e o nome de cada uma so existem em tempo de execucao: o
# nginx upstream resolve hostname UMA vez, no boot — sem NGINX Plus nao ha "resolve" dinamico —
# entao listar `server videos:8080` nao daria failover nenhum, e a lista de replicas
# individuais (`<projeto>-videos-<indice>`) precisa ser montada aqui.
#
# O nome de cada replica carrega o nome do projeto Compose, verificado por teste direto: o
# Compose desta versao nomeia replicas `<projeto>-<servico>-<indice>` (com hifen), nao
# `<servico>-<indice>` como se poderia supor por analogia ao hostname de round-robin
# `<servico>`. FIAPX_PROXY_PROJETO precisa bater com o nome de projeto que o `docker compose`
# realmente usou (o harness passa o mesmo valor que usa para montar o nome da rede).
set -eu

n="${FIAPX_VIDEOS_REPLICAS:?FIAPX_VIDEOS_REPLICAS nao definido}"
projeto="${FIAPX_PROXY_PROJETO:?FIAPX_PROXY_PROJETO nao definido}"

echo "videos-proxy: esperando $n replica(s) do videos resolverem em DNS..."
i=1
while [ "$i" -le "$n" ]; do
    alvo="${projeto}-videos-${i}"
    tentativa=0
    until getent hosts "$alvo" >/dev/null 2>&1; do
        tentativa=$((tentativa + 1))
        if [ "$tentativa" -gt 60 ]; then
            echo "videos-proxy: $alvo nunca apareceu no DNS depois de 60s" >&2
            exit 1
        fi
        sleep 1
    done
    echo "videos-proxy: $alvo resolvido"
    i=$((i + 1))
done

{
    echo "events {}"
    echo "http {"
    echo "    access_log /dev/stdout;"
    echo "    error_log /dev/stderr warn;"
    # 200 MB e o teto de upload do proprio contrato (ticket 011) — sem isto o nginx impoe um
    # limite novo (1m, o default) que a borda de tras dele nao tem.
    echo "    client_max_body_size 220m;"
    echo "    upstream videos_borda {"
    i=1
    while [ "$i" -le "$n" ]; do
        # fail_timeout curto: depois de 1 falha, o nginx tira a replica de circulacao por 2s e
        # tenta de novo sozinho — nao precisamos de retry manual do harness para isso.
        echo "        server ${projeto}-videos-${i}:8080 max_fails=1 fail_timeout=2s;"
        i=$((i + 1))
    done
    echo "    }"
    echo "    server {"
    echo "        listen 8080;"
    echo "        location / {"
    echo "            proxy_pass http://videos_borda;"
    # proxy_request_buffering fica no default (on), de proposito: e o que permite ao nginx
    # reenviar o corpo inteiro do POST para a proxima replica quando a atual morre no meio da
    # requisicao. Desligar bufferizacao (que seria a escolha natural noutro contexto, para
    # upload grande) quebraria exatamente a propriedade de resiliencia que este ticket mede.
    echo "            proxy_next_upstream error timeout invalid_header http_502 http_503 http_504;"
    echo "            proxy_next_upstream_tries $n;"
    echo "            proxy_next_upstream_timeout 10s;"
    echo "            proxy_connect_timeout 1s;"
    echo "            proxy_send_timeout 60s;"
    echo "            proxy_read_timeout 60s;"
    echo "        }"
    echo "    }"
    echo "}"
} > /etc/nginx/nginx.conf

echo "videos-proxy: nginx.conf gerado para $n replica(s):"
cat /etc/nginx/nginx.conf

exec nginx -g 'daemon off;'
