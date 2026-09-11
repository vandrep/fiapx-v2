# As legendas do *JVM Overview* não dizem de qual serviço é cada linha

- id: 095
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-11)
- bloqueado-por:
- prioridade: P3

## Origem

Pedido da sessão de 2026-09-11: *"No painel JVM Overview (OpenTelemetry) inclua os nomes dos
serviços nas legendas"*.

## O problema

Depois do [091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md), o *JVM
Overview* responde com uma série por container, mas cada legenda é só o id do container
(`3de6f12673fe`). Com quatro containers — `videos`, `notificacao` e as duas réplicas do
`extracao` — quem olha a tela não tem como saber qual linha é de qual serviço.

A causa está nas queries do dashboard, lidas da imagem (`/otel-lgtm/grafana-dashboard-jvm-metrics.json`).
Seis das oito agregam `by (instance)` — `sum by (instance)`, `sum by(le, instance)`, `/ on(instance)` —
e com isso descartam o `job`, que é onde o nome do serviço está; as legendas delas, `{{instance}}`
ou `__auto`, só enxergam o que sobrou da agregação. As outras duas, *CPU utilization* e *Classes*,
são seletores crus e ainda têm o `job`, mas a legenda fixa `{{instance}}` o ignora do mesmo jeito.

## Decisão: consertar no valor da etiqueta, não no dashboard

Há dois caminhos, e só um cabe no [ADR 0004](../../adr/0004-camada-de-observabilidade.md):

- **Sobrescrever o JSON do dashboard** trocando `by (instance)` por `by (job, instance)` e a
  legenda por `{{job}} {{instance}}`. É um arquivo inteiro derivado da imagem, a rederivar a cada
  upgrade — o mesmo preço que o `otelcol-config.yaml` já paga — para um dashboard que o ADR
  aceitou justamente por ser *"a custo zero de manutenção"*. Recusado.
- **Pôr o nome do serviço dentro da `instance`.** O processador `transform/instancia` do 091 já
  é quem escreve `service.instance.id`; ele passa a escrever `<service.name>/<host.name>` em vez
  de só `host.name`. Nenhuma query muda, o dashboard da imagem continua intocado, e a
  `instance` continua única por réplica, porque o `host.name` continua nela. Escolhido.

O separador é `/` e não `:`, porque `:` lê como porta — `rabbitmq:15692` é o valor de uma série
de *scrape* na mesma base.

## Critérios de aceite

- [x] Toda legenda do *JVM Overview* traz o nome do serviço
- [x] As duas réplicas do `extracao` continuam com `instance` distintas
- [x] `rabbitmq` e `otelcol-contrib` mantêm a `instance` que já tinham
- [x] O dashboard da imagem não é sobrescrito
- [x] `scripts/smoke.sh` continua verde, incluindo os passos 11 e 12

## Resolução

Um único statement OTTL, no `docker/observabilidade/otelcol-config.yaml` (quebrado em duas linhas
aqui só para caber):

```
set(attributes["service.instance.id"], Concat([attributes["service.name"], attributes["host.name"]], "/"))
  where attributes["service.instance.id"] == nil and attributes["service.name"] != nil and attributes["host.name"] != nil
```

A guarda ganhou `service.name != nil`: sem ela, uma série sem nome de serviço viraria `/<host>`
em vez de ficar como estava. O comentário do processador diz por que o nome vai na frente, e o
[ADR 0004](../../adr/0004-camada-de-observabilidade.md) § *Os três dashboards de fábrica* passou a
descrever o valor novo e por que o conserto mora na etiqueta e não no dashboard.

### Medido nesta sessão, contra o Compose de pé

Coletor reiniciado (`docker compose restart observabilidade`), sem erro de configuração no log.
As expressões são as oito do dashboard, lidas da imagem e rodadas pelo proxy de datasource do
Grafana com `$job` e `$instance` no `.+` do `allValue`, depois de as séries antigas saírem da
janela de staleness:

| Verificação | Resultado |
|---|---|
| `count by (job, instance) (jvm_class_count)` | `fiapx-videos/3de6f12673fe`, `fiapx-notificacao/007877116b3a`, `fiapx-extracao/3f8c02aed7fb` **e** `fiapx-extracao/33c1719cb314` |
| *Rate*, *Duration (95%)*, *CPU*, *Heap*, *GC*, *Classes*, *Threads* | 4 séries cada, todas com o nome do serviço na `instance` |
| *Error %* | vazio, e corretamente, pelo mesmo motivo do 091: não houve 5xx |
| Variável `$instance` | lista os quatro valores novos |
| `rabbitmq` / `otelcol-contrib` | `rabbitmq:15692` e o uuid do coletor — inalterados |
| `scripts/smoke.sh` | verde de ponta a ponta |

O *RED Metrics (classic histogram)* **não** ganha o nome nas legendas, e não precisa: toda query
dele soma as instâncias numa linha só (`sum(...)` ou `sum by (le)`), então nenhuma legenda mostra
`instance`. O que muda nele é só a lista suspensa `$instance`, que passa a listar os valores com o
nome do serviço.

### O que muda para quem já tem a stack de pé

As séries antigas, com a `instance` só com o id, **não somem na hora**: série que chega por OTLP
não recebe marca de staleness, então elas continuam aparecendo na consulta instantânea por até
5 min depois de o coletor reiniciar, e para sempre em qualquer janela que as cubra. Numa stack
recém-subida isso não existe. Nada no repositório consultava a `instance` pelo valor — o
`painel-infraestrutura.json`, o `alertas.yaml` e os scripts não a usam —, então nenhum outro
arquivo mudou.
