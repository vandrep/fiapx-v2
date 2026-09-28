# Os comentários do smoke e do `dashboards.yaml` ainda contam "três alertas"

- id: 118
- label: ready-for-agent
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 92d7d761)
- bloqueado-por:
- prioridade: P3

## Origem

Achado na revisão do [ticket 112](112-series-dos-alertas-conferidas-no-smoke.md), em 2026-09-14,
sobre `develop @ b748d10`. O texto é anterior ao 112, que não o corrigiu por estar fora do escopo.
O mantenedor pediu que se abrisse este ticket, e depois incluiu nele o `dashboards.yaml`.

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

O mesmo acontece em `docker/observabilidade/dashboards.yaml:28`, no comentário sobre a mesma pasta
(ticket 099): "O painel entra nela porque dela derivam as expressoes de fila — as tres regras
daquele arquivo —". O arquivo tem cinco regras agora. As expressões de fila do painel continuam
derivando das três de fila, então o que ficou errado é "daquele arquivo".

## O que fazer

Reescrever os três comentários do smoke **sem número**: "os alertas", "as regras provisionadas". A pasta
casa com todas as regras do arquivo, qualquer que seja a quantidade, e um número aqui envelhece de
novo na próxima regra. Foi pelo mesmo motivo que o [ticket 111](111-diagramas-e-narracao-coerentes-com-o-codigo.md)
tirou os números de passo da narração.

No `dashboards.yaml` o número pode ficar, porque é fato do painel: troque "as tres regras
daquele arquivo" por "as tres regras de fila daquele arquivo", ou equivalente. Não precisa de
número que dependa do total de regras.

Não mude o que o passo faz. Não renumere os passos. No `dashboards.yaml`, mude só o comentário.

## Fora do escopo

Estas menções apareceram na mesma busca e ficam fora deste ticket, a menos que o mantenedor
amplie o escopo:

- `docs/arquitetura.md:627`: "Os três alertas serem binários". As duas regras do 106 também são
  binárias (`> 0`), então a frase ficou estreita. Mas ela está num parágrafo de limitação, e não
  está errada.
- As menções que dizem "os três alertas **do 058**" ou "derivadas das dos três alertas" (de fila)
  continuam certas: `alertas.yaml:1`, `otelcol-config.yaml:11`, `painel-infraestrutura.json`,
  `docs/arquitetura.md:510` e `:615`, ADR 0004 e `map.md`. Tickets fechados, ADRs e mapa são
  registro da época e não se reescrevem (`docs/wayfinder/TRACKER.md`).

## Critérios de aceite

- [x] Nenhum comentário do `scripts/smoke.sh` diz quantas regras de alerta existem.
- [x] O comentário do `dashboards.yaml` não chama as três regras de fila de "as regras daquele
      arquivo".
- [x] Nada fora de comentário mudou no `scripts/smoke.sh` e no `dashboards.yaml` (`git diff` só em
      linhas `#`).
- [x] Linha em "Decisões até aqui" no mapa.

## Resolução

Implementado em 2026-09-15 sobre `develop @ 92d7d761`.

Os três comentários do `scripts/smoke.sh` agora usam **os alertas** e **as regras
provisionadas**, sem contar regras. O comentário do `dashboards.yaml` foi específico sobre **as
três regras de fila daquele arquivo**, porque o painel deriva suas expressões de fila delas,
enquanto a pasta continua sendo compartilhada por todas as regras provisionadas.

Nenhum comando, passo ou numeração foi alterado; a decisão foi registrada no mapa.
