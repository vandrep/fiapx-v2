# As legendas do *JVM Overview* ainda carregam o id do container, e parte delas nem o nome

- id: 096
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-11)
- bloqueado-por: 095
- prioridade: P3

## Origem

Pedido da sessão de 2026-09-11, logo depois do [095](095-nome-do-servico-nas-legendas-do-jvm-overview.md):
*"os nomes só apareceram para alguns containeres. Preciso que apareça para todos e que saiam os
identificadores dos containeres."*

## O problema, como foi medido

São dois, e só um deles é defeito.

**"Só alguns têm o nome" não é defeito do 095: é histórico.** Todo container vivo já mandava
`fiapx-videos/3de6f12673fe`. O que aparecia sem nome eram as séries **anteriores** ao 095, com a
`instance` só com o id, que continuavam dentro da janela de 1 h do dashboard. Na consulta de
séries: nos últimos 5 min, só os quatro valores novos; na última hora, os quatro novos **e** os
quatro antigos. Série que chega por OTLP não recebe marca de staleness, então cada troca de
valor da `instance` deixa a geração anterior na tela até ela sair da janela.

**O id do container na legenda é o defeito.** O 095 manteve o `host.name` porque a `instance`
precisa ser única por réplica — o `extracao` sobe com duas, e duas réplicas com a mesma
`instance` escreveriam na mesma série. Tirar o id exige outro diferenciador de réplica, legível.

## Onde está o número da réplica

Só num lugar: o nome que o Compose dá ao container, `<projeto>-<serviço>-<n>`
(`fiapx-v2-extracao-2`). Nenhum atributo que o SDK emite o carrega, e o coletor não tem como
chegar nele:

| Caminho | Por que não serve |
|---|---|
| Só o coletor | recebe `host.name` (o id) e nada mais; o `otelcol-contrib` da imagem 0.32.1 não traz processador de DNS, e o `resourcedetection/docker` descreve o host do **coletor**, não o de quem mandou |
| `hostname:` no Compose | é por serviço, não por réplica — as duas do `extracao` receberiam o mesmo |
| `/etc/hosts` de dentro do container | mapeia o IP para o id, não para o nome |
| `instance` só com o `service.name`, somando as réplicas | exigiria promover outro atributo a etiqueta na config do Prometheus da imagem — um terceiro arquivo derivado — e mudaria o dashboard de "uma linha por container" para "uma por serviço", que o ADR 0004 registra como o que ele mostra |
| **DNS embutida do Docker** (127.0.0.11) | responde o PTR do próprio IP com `fiapx-v2-extracao-2.fiapx-v2_default`. Medido de dentro das quatro réplicas, com o `nslookup` do busybox que a imagem base já traz |

## Decisão

Cada imagem sobe por um `entrypoint.sh` que pergunta à DNS do Docker o nome do próprio container,
o declara no recurso OpenTelemetry como `container.name` — atributo de convenção do OTel, e não
nome nosso (AGENTS.md § *Nomes na observabilidade*) — por `QUARKUS_OTEL_RESOURCE_ATTRIBUTES`, e dá
`exec` no JVM. O coletor tira do `container.name` só o sufixo numérico, porque o prefixo é o nome
do projeto Compose e muda com o diretório, e monta `service.instance.id` como
`<service.name>-<n>`: `fiapx-videos-1`, `fiapx-notificacao-1`, `fiapx-extracao-1`, `fiapx-extracao-2`.

O formato do 095 fica como **recuo**, num segundo statement: série sem `container.name` — imagem
anterior a esta, serviço fora do Compose, DNS que não respondeu em 3 s — continua recebendo
`<service.name>/<host.name>`, feio mas único. Sem o recuo, ela ficaria sem `instance` e voltaria a
sumir dos dashboards, que é o defeito do [091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md).

O `-1` nos serviços de uma réplica só fica. Não é id, é o número da réplica, e tirá-lo quando
`n = 1` faria `fiapx-extracao` e `fiapx-extracao-2` conviverem no dia em que alguém descer o
`extracao` para uma réplica e subir de novo.

## Critérios de aceite

- [x] Toda série **nova** chega com o nome do serviço e sem id de container
- [ ] Nenhuma legenda do *JVM Overview* traz id **na stack desta sessão** — só depois de as
  séries antigas saírem da janela de 1 h, ou de o `observabilidade` ser recriado; a escolha é
  do usuário, porque recriar zera o histórico (ver *O que isto não resolve*)
- [x] As duas réplicas do `extracao` continuam com `instance` distintas
- [x] Série sem `container.name` cai no formato do 095, e não fica sem `instance`
- [x] `rabbitmq` e `otelcol-contrib` mantêm a `instance` que já tinham
- [x] O JVM continua PID 1 (o dreno do `extracao` depende do SIGTERM)
- [x] As três cópias do `entrypoint.sh` têm guarda de divergência
- [x] `scripts/smoke.sh` continua verde

## Resolução

### O que mudou

| Arquivo | Mudança |
|---|---|
| `videos/`, `extracao/`, `notificacao/` `entrypoint.sh` | novo, três cópias byte a byte idênticas |
| os três `Dockerfile` | `COPY --chmod=755 entrypoint.sh` e `ENTRYPOINT ["/app/entrypoint.sh"]` |
| `docker/observabilidade/otelcol-config.yaml` | o `transform/instancia` ganha dois statements antes do recuo: extrai o sufixo do `container.name` para o `cache` e monta `<service.name>-<n>` |
| `scripts/verifica-entrypoint.sh` + `pom.xml` | guarda do agregador na fase `validate`, comparação sem normalização, como a do `ArchitectureConstraintsTest` |
| `AGENTS.md` | a família nova na seção das cópias deliberadas |
| `docs/adr/0004-camada-de-observabilidade.md` | o parágrafo do valor da `instance` passa a descrever os três passos (091, 095, 096) e o recuo |

### Medido nesta sessão, contra o Compose de pé

**A regra do coletor, isolada**, com quatro recursos sintéticos num único envio OTLP, antes de
reconstruir qualquer imagem:

| Recurso | `instance` |
|---|---|
| `container.name=fiapx-v2-teste-7` | `teste-096-a-7` |
| sem `container.name`, logo depois do anterior no mesmo envio | `teste-096-b/bbb222` — o recuo, sem vazar o `7` do `cache` |
| `service.instance.id=ja-tinha` + `container.name` | `ja-tinha` — a guarda `== nil` |
| `container.name=sem-numero` | `teste-096-d/ddd444` — o recuo |

**As imagens reconstruídas** (`./mvnw package` + `docker build` dos três, recriação dos
containers): as quatro réplicas declaram `container.name=fiapx-v2-<serviço>-<n>` no ambiente do PID 1,
e o PID 1 é `java`. Com as séries antigas fora da janela de staleness, as oito queries do
dashboard, lidas da imagem e rodadas com as variáveis em "All", devolvem exatamente
`fiapx-extracao-1`, `fiapx-extracao-2`, `fiapx-notificacao-1` e `fiapx-videos-1` — o *Error %* vazio,
corretamente, sem 5xx. A lista suspensa `$instance` traz os mesmos quatro. O `container.name`
também chega ao `target_info`, e aos traces e logs, que a regra do coletor não toca.

Um tropeço de medição, registrado para não se repetir: o primeiro `docker build` rodou num laço
de zsh com `$s:latest`, e o zsh leu `:l` como modificador — as tags saíram
`fiapx-videosatest:latest`, e os containers recriados subiram da imagem antiga sem que nada
reclamasse. O sintoma foi `/app/entrypoint.sh` ausente dentro do container. Use `${s}`.

A guarda reprova o `./mvnw validate` com uma linha a mais numa das cópias, citando o `diff` a
rodar, e passa com as três iguais.

`scripts/smoke.sh` verde de ponta a ponta contra as imagens novas, e `./mvnw test` na raiz verde:
456 testes (143 `videos`, 284 `extracao`, 29 `notificacao`) — nenhum Java mudou, o que ela
confere aqui é a guarda nova no `validate`. `rabbitmq:15692` e os uuids do `otelcol-contrib`
seguem com a `instance` de scrape. O `container.name` foi conferido também no Loki
(`container_name` no stream) e no Tempo (busca por `resource.container.name`).

### O que isto não resolve

**A `instance` passou a ser única por réplica, não por container — e isso tem um custo, achado
na revisão.** Um container recriado herda a `instance` do anterior (`fiapx-extracao-1` nos dois),
e as séries só diferem na etiqueta `host_name`, que o Prometheus promove mas o dashboard não
mostra. Por uns 5 min depois da recriação — o lookback do Prometheus, porque série OTLP não
recebe marca de staleness — as duas gerações convivem: o *Threads*, que agrega `by (instance)`,
mediu **72** para `fiapx-extracao-1`, a soma do container morto e do vivo, contra 32/40 de cada um;
o *CPU* e o *Classes*, que não agregam, mostram duas linhas com a mesma legenda. No formato do
095 isso não acontecia: cada geração era uma linha própria, com o id. Só a **recriação** dispara
isso (`up` com imagem ou config nova, `--force-recreate`); `restart`, `stop`/`start` mantêm o
container e o id. Foi aceito, e não contornado, porque qualquer coisa que separe as gerações teria
de estar na `instance`, e a `instance` é justamente a legenda que o pedido quer sem id.

**Sobraram séries de teste no Prometheus**: `teste_096`, dos quatro recursos sintéticos acima,
com `job` `teste-096-a` a `-d`. Não são métricas de JVM e não entram em nenhum dashboard; saem
com o histórico, pelo mesmo motivo das linhas antigas abaixo.

**As linhas antigas continuam na tela por até 1 h** depois de a stack trocar de formato — agora
são três gerações delas (id puro, `serviço/id`, `serviço-n`). Tirá-las antes exigiria apagar
série no Prometheus, e a imagem sobe sem `--web.enable-admin-api`; a outra saída é recriar o
container `observabilidade`, que zera o histórico inteiro de métrica, log e trace. Numa stack
recém-subida nada disso aparece.

**As imagens do GHCR só ganham o `entrypoint.sh` quando isto chegar à `main`.** Até lá, quem der
`docker compose pull` recebe a imagem antiga, sem `container.name`, e cai no recuo do 095 — com o
id na legenda, mas com o nome e sem sumir.
