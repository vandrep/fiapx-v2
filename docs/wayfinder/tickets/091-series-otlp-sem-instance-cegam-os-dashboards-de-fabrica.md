# As séries dos três serviços chegam sem `instance`, e isso cega os dashboards de fábrica

- id: 091
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Sessão de 2026-09-09, aberta por um pedido de painéis. O pedido colidiu com a recusa registrada
de painel curado (ver [092](092-painel-do-vao-e-a-reversao-parcial-da-recusa.md)), e no caminho
de checar o que a stack já mostrava apareceu este defeito, que é independente dele: a imagem
`grafana/otel-lgtm` **já provisiona três dashboards**, e nenhum dos três mostra qualquer coisa
dos nossos serviços. O [ADR 0004](../../adr/0004-camada-de-observabilidade.md) não os menciona
em lugar nenhum — nem para dizer que existem, nem para dizer que não servem.

## O problema

Os três dashboards da imagem — *RED Metrics (classic histogram)*, *RED Metrics (native
histogram)* e *JVM Overview (OpenTelemetry)* — filtram toda query por `instance`:

```
{__name__=~"…", job=~"$job", instance=~"$instance"}
```

As duas variáveis têm `allValue: ".+"`, então a seleção padrão "All" vira `instance=~".+"`, que
**exige a etiqueta existir e não ser vazia**. Nenhuma série nossa a tem. Elas chegam por OTLP
push e carregam `job`, `service_name`, `service_version` e `host_name` — nunca `instance`.

O mecanismo, verificado: quem traduz OTLP→Prometheus não é o coletor, é o **próprio Prometheus**,
no `otlp_http/metrics` que aponta para `http://127.0.0.1:9090/api/v1/otlp`. Ele mapeia
`service.name` → `job` e `service.instance.id` → `instance`. O primeiro chega; o segundo não é
emitido por ninguém. As duas únicas séries com `instance` na base inteira são as dos receivers de
*scrape* (`rabbitmq` e `otelcol-contrib`), que setam a etiqueta nativamente.

Medido contra a stack de pé, nas duas pontas de cada query:

| Query | Com `instance=~".+"` | Sem o matcher |
|---|---|---|
| RED · *Request Rate* | `[]` | `0.77` |
| RED · *Duration p95* | `[]` | `0.0168 s` |
| JVM · *Heap utilization* | `[]` | `123 MiB` |

Por que importa mais do que "um dashboard que ninguém abriu": a camada de observabilidade foi
comprada por um incidente de diagnóstico (06/09/2026), e a demo entrega ao avaliador um Grafana
cuja tela inicial lista três dashboards que respondem *"No data"* sobre um sistema saudável. Isso
é pior que não ter dashboard nenhum — é a stack afirmando que não há dados quando há.

## O que entregar

Popular `service.instance.id` nas séries que entram por OTLP, a partir de `host.name`, que já
está em toda série e é o id do container — portanto **único por réplica**, que é a condição que o
`extracao` impõe ao subir com duas.

O caminho é um processador na pipeline de métrica do `docker/observabilidade/otelcol-config.yaml`,
arquivo que já é nosso e já é derivado do da imagem:

```yaml
transform/instancia:
  error_mode: ignore
  metric_statements:
    - context: resource
      statements:
        - set(attributes["service.instance.id"], attributes["host.name"])
          where attributes["service.instance.id"] == nil and attributes["host.name"] != nil
```

A guarda `== nil` é o que preserva `rabbitmq` e `otelcol-contrib`, que já trazem a sua.

**Isto já foi ensaiado nesta sessão**, contra a stack real, com o coletor 0.159.0 da imagem
0.32.1, e o resultado está abaixo. O processador **já está commitado** no `develop` — o ticket
continua aberto porque o ensaio é só a primeira das linhas de aceite: o cabeçalho do arquivo e o
`ADR 0004` seguem por corrigir, e o `smoke.sh` não foi rodado desde a mudança.

| Verificação | Antes | Depois |
|---|---|---|
| `count by (job, instance) (jvm_class_count)` | sem `instance` nos três | `fiapx-videos=b277e007a96d`, `fiapx-notificacao=984815c4c962`, `fiapx-extracao=476dcdfc4998` **e** `fa4b2fa9f08c` |
| RED · *Request Rate* (All/All) | `[]` | `0.76` |
| RED · *Duration p95* (All/All) | `[]` | `0.0168 s` |
| JVM · *Heap utilization* (All/All) | `[]` | 4 séries, uma por container |
| JVM · *Threads* (All/All) | `[]` | `42 / 28 / 37 / …` |
| `rabbitmq` e `otelcol-contrib` | `instance` do scrape | **inalterada** |

Duas coisas que vêm junto e não são opcionais:

1. **O cabeçalho do `otelcol-config.yaml` passa a mentir.** Ele diz que o arquivo é derivado do
   da imagem *"com uma **única** adição: o receiver `prometheus/rabbitmq`"*. Passam a ser duas, e
   a instrução de rederivar e conferir o diff no upgrade da imagem vale para as duas.
2. **O `ADR 0004` não sabe que estes dashboards existem.** A camada foi registrada como se a
   exploração ad-hoc fosse a única superfície de leitura. Depois desta correção, dois dashboards
   mantidos pela imagem passam a responder HTTP e JVM dos três serviços a custo zero de
   manutenção — o que é exatamente o que aquele ADR prefere a um painel nosso, e merece uma
   linha lá.

## O que fica de fora, e por quê

O terceiro dashboard, *RED Metrics (native histogram)*, **continua morto depois desta correção**,
e é estrutural: ele consulta `http_server_request_duration_seconds` como histograma nativo, sem
sufixo, e o Quarkus exporta clássico (`_bucket`/`_count`/`_sum`). Nenhuma etiqueta conserta isso;
só ligar histograma exponencial no exportador, que é mudança de configuração dos três serviços
para atender um dashboard que ninguém pediu. Fica registrado como fato conhecido, não como
pendência.

## Critérios de aceite

- [ ] As séries dos três serviços chegam ao Prometheus com `instance`, uma por container
- [ ] O `extracao` com duas réplicas produz **duas** `instance` distintas
- [ ] `rabbitmq` e `otelcol-contrib` mantêm a `instance` que já tinham
- [ ] *RED Metrics (classic histogram)* e *JVM Overview* devolvem série com as variáveis em "All"
- [ ] O cabeçalho do `otelcol-config.yaml` diz que são duas adições, e quais
- [ ] O `ADR 0004` registra que os dashboards de fábrica existem, quais servem e qual não serve
- [ ] `scripts/smoke.sh` continua verde, incluindo o passo 11
