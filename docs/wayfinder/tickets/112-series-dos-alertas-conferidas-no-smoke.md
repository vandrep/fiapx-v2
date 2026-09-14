# As séries que os alertas leem, conferidas no smoke

- id: 112
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Limite deixado pelo [ticket 106](106-deteccao-de-video-preso-pelo-estado.md), em 2026-09-14,
sobre `develop @ 3da5e67`. O mantenedor aprovou abrir este ticket.

## O problema

As cinco regras de `docker/observabilidade/alertas.yaml` declaram `noDataState: OK`. Se uma
métrica que elas leem mudar de nome, a expressão devolve vazio e a regra fica `inactive` para
sempre, sem erro. Isso vale para `fiapx_videos_presos` (106) e para as séries
`rabbitmq_detailed_queue_*` do plugin do broker (058), que podem mudar num upgrade da imagem.

O passo 12 do `scripts/smoke.sh` já protege o painel curado desse jeito: lê as queries do arquivo
e reprova a que devolve série vazia. Os alertas não têm proteção equivalente. É a mesma "mentira
silenciosa" que o ADR 0004 manda cobrir.

## O que fazer

Um passo no smoke, depois do ciclo do Vídeo e da volta da observabilidade, que:

- tira de `alertas.yaml` os nomes de métrica que cada regra lê, **do arquivo**, sem lista
  repetida no script;
- confere, pelo Prometheus da stack, que cada nome tem ao menos uma série;
- confere que `fiapx_videos_presos` tem as duas séries de `estado`.

O passo julga **existência** da série, não o valor da expressão. Num sistema saudável, as cinco
regras devolvem vazio de propósito.

## Critérios de aceite

- [ ] Passo novo no `scripts/smoke.sh`, que lê os nomes de `alertas.yaml` e reprova com a regra
      e o nome que não têm série.
- [ ] Visto reprovar: renomear a métrica de uma regra, localmente, faz o passo falhar com mensagem
      acionável. Visto passar de novo depois de desfazer.
- [ ] O cabeçalho do smoke lista o passo novo, como os outros.
- [ ] `scripts/smoke.sh` verde.
