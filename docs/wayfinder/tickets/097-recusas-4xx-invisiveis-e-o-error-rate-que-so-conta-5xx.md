# As recusas 4xx não aparecem em painel nenhum, e o *Error Rate* de fábrica diz "No data"

- id: 097
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-12)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-12, diagnóstico pedido assim: *"No painel RED Metrics (classic histogram)
error rate está sem dados, ou o script executado, que deveria conter erros, não contém, ou o
painel está incorreto"*. Das três hipóteses, nenhuma se confirmou como defeito — o que o
diagnóstico achou foi um **vão**, e é dele que este ticket trata.

## O que foi medido, e por que não é defeito

Com o Compose de pé e uma corrida do `trafego.sh` no histórico, as expressões do painel foram
lidas pela API do Grafana (`/api/dashboards/uid/…`) e rodadas pelo proxy de datasource:

| Verificação | Resultado |
|---|---|
| *Request Rate* (denominador do *Error Rate*) | `0.667` — tem série |
| Numerador do *Error Rate* (`http_response_status_code=~"5.."`) | `[]` — **nenhuma série** |
| A razão, que é o painel | `[]` — "No data" |
| Status presentes em `fiapx-videos`, contador acumulado | `200`=717, `202`=116, `400`=13, `404`=13, `409`=13, `415`=13 |
| Qualquer `5..` na retenção de 24 h | nenhum, em nenhum dos três serviços |

O script **contém** erros: 52 deles, quatro classes a 13 cada, exatamente o que o cenário `erro`
do `scripts/trafego.js` promete (`erro-415`, `erro-400`, `erro-404`, `erro-409`). E o painel está
**correto**: RED define erro como erro de servidor, e um 4xx do contrato não é erro do servidor —
é a borda recusando o que deve recusar. Os dois estão certos; eles só não se encontram.

Isto já estava registrado antes desta sessão, em dois lugares, e nenhum precisa mudar:
[091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md) (*"vazio, e
**corretamente**: ele conta `5..` e o ciclo não teve nenhum"*) e o
[ADR 0004](../../adr/0004-camada-de-observabilidade.md) § *Os três dashboards de fábrica*.

## O problema, que é outro

As 52 recusas não aparecem em **painel nenhum**. O *RED classic* só conta `5..`; o
`painel-infraestrutura.json` cobre o vão do 092 — fila, Estacionamento, DLQ, `fiapx.extracao.duracao`,
log e trace — e HTTP ficou de fora de propósito, delegado ao dashboard de fábrica. O resultado é
que a demo não tem como mostrar "a borda recusa o que deve recusar", que é comportamento de
contrato conferido em teste e exercitado pelo tráfego sintético, e mesmo assim invisível na tela.

## Os dois ajustes pedidos, e o conflito com o registro

A sessão pediu mais duas coisas, e as duas caem no **JSON do dashboard de fábrica**
(`/otel-lgtm/grafana-dashboard-red-metrics-classic.json`, 15,9 kB dentro da imagem):

1. **Deixar claro no nome do painel que o "No data" é sobre 5xx** — hoje o título é `Error Rate`,
   sem qualificação.
2. **Mostrar `0%` em vez de "No data"** — medido nesta sessão: `or vector(0)` no numerador resolve
   (`(sum(rate(…5..…)) or vector(0)) / sum(rate(…))` devolveu `0`). O "No data" não é bug do
   Grafana: numerador vazio ÷ denominador é vetor vazio em PromQL.

Nenhum dos dois tem conserto fora do JSON. Título de painel e expressão de query não são
etiqueta de série — não há um `transform` no coletor que os alcance, que foi o escape do
[095](095-nome-do-servico-nas-legendas-do-jvm-overview.md). Sobrescrever aquele JSON é
**exatamente o custo que este repositório já recusou duas vezes** (091 e 095): um arquivo inteiro
derivado da imagem, a rederivar a cada upgrade, para um dashboard que o ADR 0004 aceitou por ser
*"a custo zero de manutenção"*. A diferença desta vez é honesta e vale dizer: naquelas duas havia
alternativa mais barata com o **mesmo resultado**; aqui, para o título, não há.

## Os dois caminhos

- **A — sobrescrever o *RED classic*.** Um mount a mais (o provider da imagem aponta para um
  arquivo, então um `:ro` no mesmo caminho basta). Renomeia `Error Rate` → `Error Rate (5xx)`,
  põe `or vector(0)`, e um painel de 4xx cabe no mesmo lugar. Preço: rederivar 15,9 kB de JSON a
  cada upgrade da `grafana/otel-lgtm`, e a terceira reversão da mesma decisão registrada.
- **B — pôr o HTTP da borda no nosso painel.** Uma linha nova no
  `painel-infraestrutura.json`, que é nosso e já é a home do Grafana, com dois painéis cujos
  títulos dizem o que contam — sem depender de renomear nada de fábrica:

  ```promql
  # Borda — recusas do contrato (4xx), por status
  sum by (http_response_status_code) (
    rate(http_server_request_duration_seconds_count{job="fiapx-videos", http_response_status_code=~"4.."}[5m]))

  # Borda — erros de servidor (5xx), taxa
  (sum(rate(http_server_request_duration_seconds_count{job="fiapx-videos", http_response_status_code=~"5.."}[5m]))
    or vector(0))
    / sum(rate(http_server_request_duration_seconds_count{job="fiapx-videos"}[5m]))
  ```

  Medidas nesta sessão: a primeira devolve as quatro classes (`415`, `409`, `404`, `400`, a
  `0.00346`/s cada na janela de 1 h); a segunda devolve `0` em vez de vazio. O dashboard de
  fábrica fica intocado, o *Error Rate* dele continua "No data", e o ADR ganha uma linha dizendo
  que a leitura de erro da borda mora no painel curado.
  Preço: o 092 recusou repetir HTTP no painel curado (*"seria o envelhecimento que a recusa
  temia"*), e isto abre uma exceção estreita — 4xx, que o dashboard de fábrica **não** cobre, e o
  5xx só como companhia para a razão fazer sentido.

**Recomendado: B.** Ele entrega a substância dos dois pedidos — nome que diz o que conta, e `0%`
no lugar de "No data" — no arquivo onde isso não custa manutenção, e de quebra fecha o vão dos
4xx, que é o achado real. O que B **não** entrega é o título do painel de fábrica: quem abrir o
*RED classic* direto continua vendo `Error Rate` vazio, e o que o desambigua é o ADR, não a tela.
Se esse ponto for inaceitável para a demo, A é a escolha, e o preço deve ser aceito por escrito
aqui e no mapa, como reversão explícita.

## Critérios de aceite

- [x] Decidido entre A e B, e a decisão registrada no mapa
- [x] As quatro classes de recusa (415, 400, 404, 409) do `trafego.sh` aparecem em painel
- [x] A leitura de erro de servidor mostra `0` em ciclo saudável, não "No data"
- [x] O título do painel de erro diz sobre qual faixa de status ele fala
- [x] O [ADR 0004](../../adr/0004-camada-de-observabilidade.md) § *Os três dashboards de fábrica*
      reflete a decisão — hoje ele diz que o vazio "não é defeito", o que continua verdade, mas
      não diz onde se lê a recusa 4xx
- [x] `scripts/smoke.sh` continua verde, incluindo o passo 12
- [x] Se B: o JSON do *RED classic* segue intocado. Se A: o mount novo está comentado com o
      custo de rederivação, como o `otelcol-config.yaml` faz

## Resolução

**Decidido o caminho B**, o recomendado: a leitura de erro da borda vai para o painel curado, e
o JSON do *RED classic* segue intocado. O caminho A foi recusado pela terceira vez — 091, 095 e
agora aqui — pelo mesmo motivo das duas anteriores, e com a diferença desta vez dita por escrito:
o que A entregaria a mais é **um título**, e o preço é rederivar 15,9 kB de JSON da imagem a cada
upgrade da `grafana/otel-lgtm`.

### O que mudou

| Arquivo | Mudança |
|---|---|
| `docker/observabilidade/painel-infraestrutura.json` | linha *Borda* nova, dois painéis (ids 13 e 14), em `y=36`; o painel de log desce para `y=44`; a `description` do dashboard passa a declarar a exceção |
| `docs/adr/0004-camada-de-observabilidade.md` | § *Os três dashboards de fábrica* ganha onde se lê a recusa 4xx e por que o `Error Rate` de fábrica fica como está; § *Um painel* ganha a exceção e o que ela não entrega |
| `scripts/smoke.sh` | comentário do passo 12: por que um painel de erro tem dado numa corrida verde, e por que o zero do 5xx é aprovação e não reprovação |
| `scripts/trafego.sh` | o "Fim" aponta onde ver as 4xx que o cenário `erro` acabou de gerar |
| `docs/wayfinder/map.md` | a decisão em *Decisões até aqui*, com o preço do B |

Os dois painéis são os do ticket, sem mudança de expressão:

- **Borda — recusas do contrato (4xx), por status** — `sum by (http_response_status_code)` do
  `rate(...{job="fiapx-videos", http_response_status_code=~"4.."}[5m])`, unidade `reqps`, legenda
  pelo próprio status.
- **Borda — erros de servidor (5xx), taxa sobre o total** — a razão com `or vector(0)` no
  numerador, unidade `percentunit`, que é o que faz o ciclo saudável ler `0%`.

### Medido nesta sessão, contra o Compose de pé

| Verificação | Resultado |
|---|---|
| As quatro classes no painel de 4xx | `400`, `404`, `409`, `415`, cada uma com série na janela de 1 h |
| O painel de 5xx | série única, **todas as amostras `0`** — nenhum `NaN`, nenhum vazio |
| `scripts/smoke.sh` completo | **verde**, incluindo o passo 12: 16 queries, todas com série (eram 14) |
| As duas novas no passo 12 | 676 amostras (4xx, quatro séries) e 173 amostras (5xx) |
| O Grafana releu o arquivo montado | os 14 painéis na ordem nova pela API, sem recriar container |

Duas coisas medidas que valem registro, porque contrariam o que se suporia:

- **O que o próprio `smoke.sh` alimenta é o `404`, e só ele.** O passo 9 subiu o contador daquele
  status de 13 para 14 na corrida; o `409` do passo 7 e o `415` não apareceram no contador. Não
  faz falta: basta uma classe para a query ter série e o passo 12 aprovar. As outras três vêm de
  uma corrida do `trafego.sh`, que é quem exercita o cenário `erro` inteiro — e é para olhar os
  painéis que aquele script existe.
- **`401` conta como 4xx neste painel.** Medido à parte: três `GET /videos` sem token criaram a
  série `http_response_status_code="401"` no `fiapx-videos`, que antes não existia. É o desejado —
  recusa de autenticação é recusa da borda, e o RED de fábrica também não a conta —, mas quem ler
  o painel numa demo precisa saber que uma linha de `401` ali é o OIDC funcionando.

### O preço do B, por escrito

Quem abrir o *RED Metrics (classic histogram)* direto continua vendo `Error Rate` sem
qualificação e vazio em ciclo saudável. Quem desambigua é o ADR 0004, não a tela. Esse era o
único item em que A ganhava, e ele não paga a rederivação do JSON de fábrica.

A exceção à recusa do 092 — *"repetir HTTP aqui seria o envelhecimento que a recusa temia"* —
fica estreita por construção, e o argumento da própria recusa é o que a delimita: o dashboard de
fábrica **não** mantém série de `4..`, então não há repetição; o `5xx` é repetição e entra assim
mesmo, só para a razão ter denominador e para a palavra "erro" ter um título que diz de qual
faixa fala. Taxa e duração do HTTP continuam fora, e o link do topo continua sendo a resposta
para elas.

## Correção (revisão da mesma sessão)

O `/code-review` apontou que a Resolução acima diz *"O que o próprio `smoke.sh` alimenta é o `404`,
e só ele"*, e que isso não fecha com o parágrafo seguinte, que mede o `401` virando série. Os dois
são medição, e a frase fica como está porque é o que a corrida devolveu; o que faltava é a
ressalva:

- O passo 3 do `smoke.sh` cobra `401` sem token, e `401` **conta** neste painel — três
  `GET /videos` sem token criaram a série `http_response_status_code="401"` no `fiapx-videos`,
  onde antes não havia nenhuma.
- Ainda assim, no fim da corrida que fechou este ticket **não havia** série de `401`. Não há
  explicação medida para isso, e nenhuma foi inventada aqui.
- Consequência prática, e a única que importa: quem contar com o passo 12 conte com o `404` do
  passo 9, que foi medido subindo de 13 para 14. É o que o comentário do passo 12 passou a dizer.

Duas outras coisas saíram da revisão e foram aplicadas no mesmo commit:

- A `description` do painel de 4xx passou a usar as palavras de `docs/contratos/http-videos.md`
  em vez de parafraseá-las (*campo `arquivo` ausente ou vazio*, *content-type ou extensão fora da
  lista*, o `409` como o *"ainda não"* que se distingue do `404` *"nunca"*), e a avisar que o
  `401` aparece ali — antes esse aviso só existia neste ticket, onde ninguém que abre o painel
  numa demo vai lê-lo. O ADR ganhou a mesma ressalva.
- O comentário do passo 12 diz por que o painel de 5xx **não** entra na lista de exceções do
  passo, embora a `description` dele admita um vazio legítimo: aquele vazio exige borda ociosa por
  mais de 5 min, e ali o próprio smoke acabou de gerar tráfego na janela consultada.

A revisão não achou violação dura de padrão. O que ela registra como juízo, e que fica aceito: o
argumento do `or vector(0)` aparece no ADR, no mapa, neste ticket e na `description` do painel. O
ADR é a autoridade; os outros três são registro histórico (mapa e ticket) e a única explicação que
existe **na tela** (a `description`), e é por isso que nenhum deles virou ponteiro.
