# Os comentários do smoke ainda contam "três alertas"

- id: 118
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado na revisão do [ticket 112](112-series-dos-alertas-conferidas-no-smoke.md), em 2026-09-14,
sobre `develop @ b748d10`. O texto é anterior ao 112, que não o corrigiu por estar fora do escopo.
O mantenedor pediu que se abrisse este ticket.

## O problema

`docker/observabilidade/alertas.yaml` tem **cinco** regras desde o
[ticket 106](106-deteccao-de-video-preso-pelo-estado.md): três de fila, do 058, e duas de Vídeo
preso. Três comentários do `scripts/smoke.sh`, todos sobre a pasta que o painel divide com os
alertas (ticket 099), ainda falam em três:

- linha 28, no cabeçalho: "a pasta que ele divide com os tres alertas";
- linha 453, no passo 12: "O painel mora na MESMA pasta dos tres alertas (ticket 099)";
- linha 472, no passo 12: "as tres regras provisionadas apontam para o mesmo `folderUid` que o
  painel".

O código está certo. A guarda da linha 472 consulta `/api/v1/provisioning/alert-rules` e conta as
regras fora da pasta, sem depender de quantas são. As cinco ficam cobertas. O defeito está só na
narração, que diz "três" logo acima de um passo 14 que imprime "5 regras de alerta".

## O que fazer

Reescrever os três comentários **sem número**: "os alertas", "as regras provisionadas". A pasta
casa com todas as regras do arquivo, qualquer que seja a quantidade, e um número aqui envelhece de
novo na próxima regra. Foi pelo mesmo motivo que o [ticket 111](111-diagramas-e-narracao-coerentes-com-o-codigo.md)
tirou os números de passo da narração.

Não mude o que o passo faz. Não renumere os passos.

## Fora do escopo

Estas menções apareceram na mesma busca e ficam fora deste ticket, a menos que o mantenedor
amplie o escopo:

- `docker/observabilidade/dashboards.yaml:28`: "as tres regras daquele arquivo". O arquivo agora
  tem cinco. As expressões de fila do painel derivam das três de fila, então a frase se corrige
  com "as tres regras de fila", e não com um número novo. É o candidato mais próximo a este ticket.
- `docs/arquitetura.md:627`: "Os três alertas serem binários". As duas regras do 106 também são
  binárias (`> 0`), então a frase ficou estreita. Mas ela está num parágrafo de limitação, e não
  está errada.
- As menções que dizem "os três alertas **do 058**" ou "derivadas das dos três alertas" (de fila)
  continuam certas: `alertas.yaml:1`, `otelcol-config.yaml:11`, `painel-infraestrutura.json`,
  `docs/arquitetura.md:510` e `:615`, ADR 0004 e `map.md`. Tickets fechados, ADRs e mapa são
  registro da época e não se reescrevem (`docs/wayfinder/TRACKER.md`).

## Critérios de aceite

- [ ] Nenhum comentário do `scripts/smoke.sh` diz quantas regras de alerta existem.
- [ ] Nada fora de comentário mudou no `scripts/smoke.sh` (`git diff` só em linhas `#`).
- [ ] Linha em "Decisões até aqui" no mapa.
