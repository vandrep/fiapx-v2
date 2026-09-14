# Proxy de carga aguenta a rajada padrão do `borda.sh`

- id: 114
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Limite de conexões declarado no proxy de carga, com a derivação escrita ao lado.
- [ ] `scripts/carga/borda.sh escala 1` com o default de 400 conexões: zero falhas de conexão e
      os seis critérios verdes. Comando, imagem e resultado registrados.
- [ ] Nenhuma linha `worker_connections are not enough` no log do `videos-proxy` na rodada.
