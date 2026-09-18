# As séries que os alertas leem, conferidas no smoke

- id: 112
- label: ready-for-agent
- status: fechado
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

- [x] Passo novo no `scripts/smoke.sh`, que lê os nomes de `alertas.yaml` e reprova com a regra
      e o nome que não têm série.
- [x] Visto reprovar: renomear a métrica de uma regra, localmente, faz o passo falhar com mensagem
      acionável. Visto passar de novo depois de desfazer.
- [x] O cabeçalho do smoke lista o passo novo, como os outros.
- [x] `scripts/smoke.sh` verde.

## Resolução

Escrito em 2026-09-14 sobre `develop @ ed0e9b6`. Só `scripts/smoke.sh`, mais um ponteiro no
cabeçalho de `docker/observabilidade/alertas.yaml`. Nenhum código de serviço mudou.

**O passo 14.** Ficou no fim do smoke, depois do 13: o 11 já religou a observabilidade, e o 12 já
esperou a exportação OTLP do `videos` chegar. O passo tira do arquivo cada `expr` com o `title` da
regra, e manda a expressão ao `/api/v1/parse_query` do Prometheus da stack (3.14). Da árvore que
volta, pega todo nó `vectorSelector` e remonta o seletor com nome e matchers. Depois confere, por
consulta instantânea `count(<seletor>)`, que cada seletor tem série. Faltou algum, o passo espera
em voltas de 10 s, com teto de 90 s. No teto, lista cada seletor sem série com a regra dele e
reprova.

**Seletor inteiro, e não só o nome.** O ticket pedia os nomes, mais as duas séries de `estado` de
`fiapx_videos_presos`. Conferir o seletor com os rótulos cobre as duas coisas sem lista no script:
`estado="PROCESSANDO"` e `estado="RECEBIDO"` já estão nas `expr` das regras 4 e 5. Cobre também
fila renomeada (`queue="extracao.extrair.dlq"`), que calaria a regra 2 do mesmo jeito. Medido na
stack de pé: as filas vazias têm série com valor 0, e o gauge do 106 exporta as duas séries de
`estado` com 0. Nenhum dos 7 seletores precisou de exceção.

**Por que o parser do Prometheus.** Uma regex teria de separar métrica de `max`, `sum`, `and`,
`on ()`, número e string com chave. Errando para um lado, reprova regra correta; errando para o
outro, pula a métrica que devia julgar. O endpoint devolve a árvore que o próprio Prometheus
avalia.

**Guardas do próprio passo.** O número de `- uid:` do arquivo precisa bater com o de regras de
que o awk leu `expr`. A regra é identificada pelo `uid`, e não pelo título, porque o título pode
repetir, e regra sem `title:` herdaria o da regra de cima. `expr` fora de aspas simples reprova, em
vez de ser lida pela metade. O Prometheus sem resposta reprova com uma mensagem, e a expressão que
ele não entende, com outra, que traz o erro dele. Regra sem nenhum `vectorSelector` reprova, porque
o passo não teria o que julgar nela. Na conferência, resposta não numérica reprova como Prometheus
fora do ar, e não como série ausente, igual à separação do passo 12. As três primeiras reprovações
foram vistas: porta errada, `}` apagada e `estado="recebido"`.

**Visto reprovar.** Com o `alertas.yaml` mudado localmente em dois pontos, a métrica da regra 4
para `fiapx_videos_presas` e a fila da regra 2 para `extracao.dlq`, o passo esperou os 90 s e
reprovou com:

```
    sem série  rabbitmq_detailed_queue_messages{queue="extracao.dlq"}  (regra 'DLQ do extracao com mensagem')
    sem série  fiapx_videos_presas{estado="PROCESSANDO"}  (regra 'Video preso em PROCESSANDO')
    FALHOU  regra de alerta lendo série que não existe no Prometheus 90s depois — ...
```

Desfeita a mudança, passou de novo na primeira volta, com os 7 seletores. Depois da revisão,
o mesmo se viu com só o valor do rótulo trocado (`estado="recebido"`).

**Smoke.** `scripts/smoke.sh` completo e verde duas vezes, antes e depois das correções da revisão, contra a stack já de pé: 14 passos, `exit 0`.

**Limites.**

- O passo lê o arquivo, e o Grafana só relê o arquivo quando reinicia. Se alguém editar o
  `alertas.yaml` sem reiniciar a observabilidade, o passo julga o arquivo, e não a regra que está
  sendo avaliada.
- Série antiga ainda dentro do lookback de 5 min aprova um nome que acabou de sumir. No upgrade
  da imagem o container é recriado, e com ele a base do Prometheus. Então isso só pesa se o
  emissor mudar o nome com a stack de pé.
- As duas séries de `estado` ficam cobertas porque as regras 4 e 5 filtram por elas. Se uma regra
  passar a ler `fiapx_videos_presos` sem esse rótulo, o passo deixa de conferir as duas, e ninguém
  é avisado. É o preço de não repetir a lista no script.
- Existir série não prova que a expressão dispara. Isso continua medido à mão, como no 058 e no
  106.
