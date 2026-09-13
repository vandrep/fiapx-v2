# Recusa por capacidade antes de receber o corpo

- id: 108
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por: 104
- prioridade: P2

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)). A pergunta foi se o sistema
consegue impedir novos pedidos quando não há recurso para todos.

## O problema

Não há recusa por sobrecarga em lugar nenhum: sem `429` ou `503`, sem teto de uploads
simultâneos, sem guarda de disco. A extração não satura, porque vira backlog na fila quorum, e
isso é o amortecedor de pico que o enunciado pede. Quem satura é a borda: o Vert.x grava o
corpo inteiro, até 200 MB, no volume `fiapx-uploads` **antes** de o resource rodar, e o disco
cheio vira `500`.

## Decisão

- Recusar pelo **recurso local da borda**, não pelo backlog da fila. O backlog só alimenta
  alerta; recusar por ele trocaria "não perder" por "não aceitar" justamente no pico.
- A decisão acontece **antes de ler o corpo**, num filtro de rota: uploads em andamento na
  réplica acima de um teto, ou `Content-Length` declarado maior que o espaço livre do volume de
  uploads, resultam em `503` com `Retry-After`. Upload sem `Content-Length` é contido pelo teto
  de concorrência.
- Falha ao gravar no MinIO, a primeira escrita, quando nada mais foi gravado, também vira
  `503`, não `500`.
- Recusa explícita não é Vídeo perdido: o sistema não assumiu o Vídeo.

Fora de escopo, registrado no [109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md):
cota por Dono e fila justa.

## Critérios de aceite

- [ ] Teste: requisição acima do teto recebe `503` + `Retry-After` sem que o corpo chegue ao
      volume de uploads.
- [ ] Teste: `Content-Length` maior que o espaço livre recebe `503`.
- [ ] Teto de concorrência derivado do tamanho do volume e do limite de 200 MB, e calibrado com
      `scripts/carga/borda.sh`; comando, configuração e resultado registrados.
- [ ] `503` documentado em `docs/contratos/http-videos.md`, em `problem+json` se o ponto de
      recusa permitir; se não permitir, registrar a inconsistência como a do `413`.
- [ ] Linha em "Decisões até aqui" no mapa.
- [ ] `./mvnw test` e `scripts/smoke.sh` verdes.
