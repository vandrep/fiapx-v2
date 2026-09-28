# O *Error %* do *JVM Overview* diz "No data", e o conserto do 097 não serve para ele

- id: 098
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-12)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-12, diagnóstico pedido assim: *"o Error % do painel JVM Overview
(OpenTelemetry) fica com nodata. Falta identificar qual o erro dele, e talvez seja melhor colocar
0% como padrão quando não houver dados"*.

## Conflito com o registro, primeiro

O pedido usa a palavra "erro" para o painel, e o registro diz o contrário em dois lugares:
o [ADR 0004](../../adr/0004-camada-de-observabilidade.md) § *Os três dashboards de fábrica*
(*"o único que continua podendo aparecer vazio, e isso não é defeito"*, escrito sobre o painel de
erro do *RED classic*) e a Resolução do [096](096-legendas-do-jvm-overview-sem-id-de-container.md)
(*"o *Error %* vazio, **corretamente**, sem 5xx"*, este sobre o painel do *JVM Overview*). A
medição desta sessão confirma as duas: **não há defeito no painel**. O que há é um achado novo, e
ele é sobre o conserto, não sobre o sintoma.

## O que foi medido

Com o Compose de pé, a expressão foi lida **do próprio dashboard** pela API do Grafana
(`/api/dashboards/uid/b91844d7-…`, painel id 50) e rodada pelo proxy de datasource, com `$job` e
`$instance` em "All" (`.*`) e `$__rate_interval` em `5m`:

| Verificação | Resultado |
|---|---|
| Denominador (total de requisições, por `instance`) | 4 séries — `fiapx-videos-1`, `fiapx-extracao-1`, `fiapx-extracao-2`, `fiapx-notificacao-1` |
| Numerador (`http_response_status_code=~"5.*"`) | `[]` — **nenhuma série** |
| A razão, que é o painel | `[]` — "No data" |
| Qualquer `5..` na retenção | nenhum — os status presentes são `200`=669, `202`=31, `400`=2, `404`=2, `409`=2, `415`=2 |
| Painel *Rate* vizinho (id 51), mesmo seletor sem o filtro de status | 4 séries |

A causa é a **mesma** do 097: numerador vazio dividido por denominador é vetor vazio em PromQL.
Os três `or` da expressão não salvam — eles são alternativas de **nome de métrica** (`http_server_*`
em quatro grafias, mais o `outcome="SERVER_ERROR"` do Micrometer), e as três razões ficam vazias
pelo mesmo motivo. Que os dois workers apareçam no denominador sem ter borda HTTP também não é
defeito: é a sonda de saúde em `:8080`.

## O achado: o `or vector(0)` do 097 **não** conserta este painel

Medido nesta sessão, contra a mesma stack:

| Expressão | Resultado |
|---|---|
| o painel como está | `[]` |
| `(numerador or vector(0)) / on (instance) denominador` — a receita do 097 | **`[]`, continua vazio** |
| `(numerador or (denominador * 0)) / on (instance) denominador` | 4 séries, todas `0` |

O motivo é a diferença de forma entre os dois painéis. O do 097 é uma razão **agregada**
(`sum(...)`/`sum(...)`, sem `by`), e ali `vector(0)` — que é uma série **sem etiqueta nenhuma** —
casa com o denominador sem etiqueta. Este é uma razão **por `instance`**, com
`sum by (instance)(...) / on (instance) sum by (instance)(...)`: o `vector(0)` entra sem
`instance`, o `on (instance)` não acha par para ele e o resultado é vazio de novo. O zero por
instância tem de **nascer com a etiqueta**, e a forma mais curta de consegui-lo é multiplicar o
denominador por zero.

Isto vale registrar mesmo que nada mude na tela: a receita do 097 está escrita no ADR, no mapa, no
ticket 097 e na `description` de um painel, e ela é verdadeira **só** para razão sem `by`. Quem a
copiar para um painel por `instance` verá o mesmo "No data" e concluirá que o Prometheus está
errado.

## Os dois caminhos, e o preço de cada um

1. **A — sobrescrever o JSON de fábrica do *JVM Overview*.** É o único jeito de o painel na tela
   ler `0%`: expressão de query e título de painel não são etiqueta de série, então nenhum
   `transform` do coletor os alcança. Preço: um mount a mais e um arquivo derivado da imagem a
   rederivar a cada upgrade da `grafana/otel-lgtm` — **exatamente a decisão que este repositório
   já recusou três vezes** (091, 095, 097). Aceitá-la aqui é a quarta ocasião e a primeira
   reversão; ela tem de ser escrita no mapa como tal.
2. **B — deixar o painel de fábrica como está.** A leitura de erro de servidor da borda **já**
   mostra `0%` em ciclo saudável desde o 097, no painel curado (*Borda — erros de servidor (5xx),
   taxa sobre o total*), que é a home do Grafana. O que B não entrega é o `0%` na tela do *JVM
   Overview* para quem abrir aquele dashboard direto; o que o desambigua é o ADR, não a tela — a
   mesma conclusão, e o mesmo preço, do 097.

**Recomendado: B**, pelo motivo das três recusas anteriores, com uma diferença em relação ao 097 e
a favor de B: lá o painel curado ainda não tinha leitura de 5xx nenhuma, e aqui já tem. O que
muda em B é só documentação — a ressalva do `or vector(0)` acima, que é o achado real deste
ticket.

## Critérios de aceite

- [x] Decidido entre A e B, e a decisão registrada no mapa
- [x] O [ADR 0004](../../adr/0004-camada-de-observabilidade.md) diz que a receita do `or vector(0)`
      vale para razão agregada e **não** para razão por `instance`, e qual é a forma que vale lá
- [x] A tabela dos três dashboards de fábrica no ADR menciona o *Error %* do *JVM Overview* junto
      com o do *RED classic* — hoje a ressalva "pode aparecer vazio, e não é defeito" está escrita
      só sobre o primeiro
- [ ] Se A: o mount novo comentado com o custo de rederivação, como o `otelcol-config.yaml` faz, e
      a reversão escrita no mapa
- [ ] Se A: o zero nasce com a etiqueta `instance` (`or (denominador * 0)`), não com `vector(0)`
- [x] `scripts/smoke.sh` continua verde

## Resolução

**Decidido o caminho B**, o recomendado, e o **A recusado pela quarta vez** (091, 095, 097, e
aqui). Nada fora de documentação mudou: o JSON de fábrica do *JVM Overview* segue intocado, o
painel segue vazio em ciclo saudável, e o `0%` que o pedido quer já existe desde o 097 no painel
curado, que é a home do Grafana.

Os dois critérios prefixados por *Se A* não se aplicam, e ficam desmarcados de propósito. O
segundo deles — o zero nascer com a etiqueta `instance` — continua valendo como aviso, e é onde
ele está registrado que mudou: saiu do "se algum dia" e entrou no ADR.

### O que mudou

| Arquivo | Mudança |
|---|---|
| `docs/adr/0004-camada-de-observabilidade.md` | § *Os três dashboards de fábrica*: a ressalva "painel de erro vazio não é defeito" passa a valer para os **dois** painéis, com a medição do denominador por `instance` e a sonda de saúde dos workers; § *Um painel*: parágrafo novo com o limite da receita do `or vector(0)`, e a forma que vale em razão por etiqueta |
| `docs/wayfinder/map.md` | linha nova em *Decisões até aqui*, com a quarta recusa do A e o achado do conserto; a nota da fronteira registra o segundo diagnóstico sem defeito e manda o terceiro pedido igual começar pelo ADR |
| este ticket | `## Resolução` |

### O que **não** mudou, e o porquê

- **O JSON de fábrica.** Mesmo argumento das três recusas anteriores, e aqui ele é mais forte que
  no 097: lá o A entregaria um título que não existia em lugar nenhum; aqui o que o A entregaria
  é um `0%` que **já está na tela**, na home, a um clique de distância.
- **As queries do `painel-infraestrutura.json`.** A razão de 5xx dele responde a mesma pergunta,
  agregada em vez de por `instance`. Uma segunda versão por `instance` seria a repetição que o 092
  recusou, e desta vez sem o argumento que abriu a exceção do 097: a leitura já existe. A
  `description` daquele painel, sim, mudou — ver *Correção*, abaixo.
- **Nenhum código Java, nenhuma config de coletor, nenhum script.** Por isso não há teste de
  regressão a escrever: o que este ticket entrega é registro, e o `scripts/smoke.sh` já cobre o
  painel que carrega a leitura.

### Medido nesta sessão

O loop do diagnóstico (expressão lida do próprio dashboard pela API do Grafana, rodada pelo proxy
de datasource) continua **vermelho**, e isso é o resultado esperado do B: o painel de fábrica
segue vazio.

`scripts/smoke.sh` completo, contra o Compose de pé: **verde**, incluindo o passo 12 — 16 queries
do painel curado, todas com série, e entre elas *Borda — erros de servidor (5xx), taxa sobre o
total* com 82 amostras. É essa a leitura que mostra `0%`; ela tem série e vale `0`, que é a
afirmação que o pedido queria em vez do silêncio do "No data".

## Correção (revisão da mesma sessão)

O `/code-review` nos dois eixos rodou contra a Resolução acima e achou cinco coisas, todas
aplicadas no mesmo commit. Nenhuma delas é violação dura; a lista existe porque quatro são sobre
**precisão do registro**, que é o produto deste ticket.

- **A grafia do seletor divergia entre o ADR e a medição deste ticket.** O ADR dizia que os dois
  painéis contam `5..`; o *JVM Overview* conta `5.*`, e é isso que a tabela de medição registra.
  O ADR passa a dizer as duas grafias, e que são a mesma pergunta escrita de dois jeitos — cada
  JSON escolheu o seu.
- **A tabela dos três dashboards de fábrica seguia intocada**, e o critério de aceite fala da
  tabela, não do parágrafo ao lado dela. A célula do *JVM Overview* passa a dizer que o `Error %`
  dele é HTTP e vazio em ciclo saudável; o `[x]` daquele critério agora é literal.
- **A edição tinha tirado o qualificador de "os dois dashboards".** Com três linhas na tabela
  logo acima, "os dois" ficou sem âncora; voltou a ser "os dois dashboards **que servem**".
- **"sonda de saúde" era sinônimo novo sem decisão.** O repositório chama isso de *health check*
  (`AGENTS.md`, `map.md`), e `docs/agents/domain.md` manda registrar a lacuna em vez de inventar
  o segundo nome. Trocado pelo termo que já existe.
- **A `description` do painel de 5xx continuava ensinando o `or vector(0)` sem o qualificador** —
  e ela é, por decisão do 097, *a única explicação que existe na tela*. Era o vetor de propagação
  que este ticket nomeia: quem copia a receita copia de lá, não do ADR. A `description` ganhou a
  ressalva e o formato que vale em razão por etiqueta. Só o texto mudou; **nenhuma query foi
  tocada**, e o passo 12 do `smoke.sh`, que lê as queries daquele arquivo, foi rodado de novo
  depois da edição.

Um sexto achado fica **recusado, e com o motivo**: a revisão sugeriu que o critério do
`scripts/smoke.sh` não é verificável pelo diff, o que é verdade e não muda nada — ele nunca foi
verificável por diff em ticket nenhum deste repositório. A execução está registrada em *Medido
nesta sessão*, como nos vizinhos.
