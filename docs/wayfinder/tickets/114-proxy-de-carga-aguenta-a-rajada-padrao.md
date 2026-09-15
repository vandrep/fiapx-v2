# Proxy de carga aguenta a rajada padrão do `borda.sh`

- id: 114
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-15, SHA inicial dabb22a)
- bloqueado-por:
- prioridade: P3

## Origem

Calibração do [ticket 108](108-recusa-por-capacidade-antes-do-corpo.md), em 2026-09-14, sobre
`develop @ 0ec5e9b`. O mantenedor aprovou este corte em 2026-09-14.

## O problema

`scripts/carga/borda.sh escala 1` com o default de 400 conexões simultâneas não mede o `videos`.
Mede o proxy da frente dele. Na rodada `ticket108-derivado`, 137 dos 400 envios falharam com
status 0 (broken pipe, connection reset, EOF), e o nginx do `videos-proxy` registrou
`512 worker_connections are not enough`. O `videos` não recusou nada. Os 263 aceitos chegaram a
terminal.

O `nginx.conf` gerado por `scripts/carga/proxy/entrypoint.sh` não declara bloco `events`, então
fica com as 512 conexões do padrão. Cada envio ocupa duas: a do cliente e a da réplica. As
rodadas úteis do [ticket 028](028-escala-da-borda.md) e do 108 saíram com `FIAPX_VUS` reduzido,
e é esse o sintoma.

O ticket 028 registrou 400 envios de 400 aceitos sob rajada, em outra máquina. Não se sabe se
aquela corrida esbarrou no mesmo limite. Este ticket não precisa responder isso, só registrar se
achar.

## O que fazer

Declarar no proxy de carga o limite de conexões de que a rajada padrão precisa, com a conta ao
lado: envios simultâneos × 2, mais folga. O `docker-compose.yml` da demo não muda, porque o proxy
só existe no overlay.

## Critérios de aceite

- [x] Limite de conexões declarado no proxy de carga, com a derivação escrita ao lado.
- [x] `scripts/carga/borda.sh escala 1` com o default de 400 conexões: zero falhas de conexão e
      os seis critérios verdes. Comando, imagem e resultado registrados.
- [x] Nenhuma linha `worker_connections are not enough` no log do `videos-proxy` na rodada.

## Resolução

Implementado em 2026-09-15 sobre `develop @ dabb22a`.

**Limite.** O `nginx.conf` gerado por `scripts/carga/proxy/entrypoint.sh` declara
`events { worker_connections 1024; }`, com a conta no comentário ao lado: sem `worker_processes` o
nginx sobe um worker só, e o limite vale por worker. Cada envio em voo ocupa no máximo duas
conexões, então 400 × 2 = 800 é teto, não pico medido: com `proxy_request_buffering on` a conexão
com a réplica só abre depois de o corpo inteiro chegar ao proxy, e não há `stub_status` para ver o
pico real. A folga até 1024 (+224) é para o healthcheck, a amostra do oráculo e o que o k6 abrir a
mais, e não foi medida. Acima de 400 envios simultâneos (`FIAPX_VUS`) a folga começa a ser
consumida, e a conta precisa ser refeita. O descritor de arquivo não limita antes: o `ulimit -n`
do container `nginx:1.27-alpine` é 1073741816. A configuração gerada passou em `nginx -t`. O
`docker-compose.yml` da demo não mudou.

### Rodada

| | |
|---|---|
| Comando | `FIAPX_ROTULO=ticket114-escala-n1 systemd-inhibit --what=sleep:idle scripts/carga/borda.sh escala 1` (default: 400 envios, 400 conexões, `controle-3s.mp4`, 2 réplicas de `extracao`) |
| Imagens | `videos` `ghcr.io/vandrep/fiapx-videos:latest` `17ce23bc1408` (build local do 108, anterior à mudança de `application.properties` do 113); `extracao` `5a42fa3a2648`; proxy `nginx:1.27-alpine` `6769dc3a703c` |
| Janela | 2026-09-15 05:50–05:54 −03 |
| Rajada | **400 `202` em 400 envios, zero recusas e zero falhas de conexão**; rajada em 24,7 s |
| Latência do `202` | mediana 14,0 s, p95 18,9 s, máximo 21,4 s |
| Drenagem | 400/400 `CONCLUIDO` em 125 s (limite 600 s) |
| Critérios | os seis verdes: zero não-`202`, todos terminais, zero presos, amostra de 10 pela API, zero `FALHOU`, zero frames errados |
| Log do `videos-proxy` | nenhuma linha `worker_connections are not enough`, e nenhuma linha `[error]`, `[crit]`, `[alert]` ou `[emerg]` |
| Log do `videos` | nenhuma `Envio recusado por capacidade` |

A rodada valida o proxy, não o `videos` do `develop`: a imagem é anterior ao 113.

A mediana do `202` subiu de 6,2 s (rodada A2 do 108, 200 VUs) para 14,0 s. A causa **não foi
investigada**, e a comparação não controla o aquecimento. O indício aponta para partida a frio, e
não para o dobro de envios. O `videos` terminou o boot às 08:51:34 UTC, e a rajada começou segundos
depois. Entre 08:51:43 e 08:51:45 o `BlockedThreadChecker` acusou event loops bloqueados de 2,2 s a
4,3 s. As respostas saíram represadas por volta de 08:52:00. O access log do proxy não tem
`$upstream_response_time`, então nada separa a espera no proxy da espera no `videos`. Os event
loops bloqueados sob rajada ficam registrados como observação, e não viraram ticket.

### Se o 028 esbarrou no mesmo limite

Não achei a resposta. O 028 registra que as rodadas de `mata-replica` saíram com `FIAPX_VUS=40`,
mas não diz com quantos VUs rodaram as de `escala`, nem traz o log do proxy. Numa máquina de
6 vCPU cada envio fica menos tempo em voo, e é possível que a rajada nunca tenha passado de 512
conexões simultâneas. Isso fica como hipótese, não foi medido.

### Validação e revisão

`./mvnw test` não rodou: a mudança é o `entrypoint.sh` do overlay de carga, que nenhum teste do
Maven nem nenhuma guarda do agregador lê. O que a valida é o `nginx -t` e a rodada acima.

`/code-review` em dois eixos. Corrigido depois dela: a folga declarada deixou de valer até ~500
envios simultâneos e passou a acabar em 400, porque os 224 de folga já estão reservados; 800 virou
teto, não pico medido; "envios" e "conexões" deixaram de se confundir; e a explicação da mediana do
`202`, que a evidência não sustentava, virou "não investigado", com o indício de partida a frio.

## Desdobramento

Os event loops bloqueados no começo da rajada viraram o
[ticket 120](120-event-loops-bloqueados-no-primeiro-envio-sob-rajada.md), a pedido do mantenedor.
