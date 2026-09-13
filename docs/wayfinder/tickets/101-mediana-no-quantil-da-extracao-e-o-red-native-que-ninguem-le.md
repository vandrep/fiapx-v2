# A mediana no gráfico de quantil da Extração, e o *RED Metrics (native histogram)* que ninguém lê

- id: 101
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-13)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-13, pedido assim: *"inclua a mediana no gráfico Extração — p95 e p99 da duração,
por resultado do painel FIAP X — a infraestrutura está saudável?. Remova o RED Metrics (native
histogram) já que não está sendo utilizado"*.

São duas mudanças independentes, no mesmo assunto — o que a tela de observabilidade mostra a quem
abre a demo — e por isso num ticket só: uma acrescenta leitura ao painel curado, a outra tira uma
tela que não tem leitor.

## Conflito com o registro, primeiro

A segunda metade contradiz duas decisões escritas, e as duas são deliberadas:

1. O [091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md) § *O que fica de fora*
   deixou o *RED Metrics (native histogram)* de pé como **fato conhecido, não pendência**, e o
   [ADR 0004](../../adr/0004-camada-de-observabilidade.md) § *Os três dashboards de fábrica* o
   registrou numa tabela com **"não"** na coluna *Serve?*.
2. O comentário de `docker/observabilidade/dashboards.yaml` dizia que o `grafana-dashboards.yaml`
   da imagem **não é tocado**, porque sobrescrevê-lo custa rederivá-lo a cada upgrade.

Nenhuma das duas é argumento contra o pedido, e é isso que o desbloqueia. A primeira decidiu **não
consertar** o dashboard — ligar histograma exponencial nos três exportadores para atender uma tela
que ninguém pediu —, e não que ele deva continuar na lista; tirá-lo é a outra resposta à mesma
constatação. A segunda pesa 15,9 kB de JSON derivado (o preço que o ADR 0004 recusou quatro vezes,
a última no 098) contra **500 bytes de YAML** com três providers: o mesmo tipo de custo, duas
ordens de grandeza menor, e já pago uma vez pelo `otelcol-config.yaml`.

O que **não** muda: o motivo estrutural continua o mesmo — histograma nativo contra exportador
clássico. Este ticket não conserta aquele dashboard; tira-o da tela.

## O defeito, em uma frase cada

**A mediana.** O painel *Extração — p95 e p99 da duração, por resultado* responde a cauda, e o
painel de média ao lado responde "a duração típica" por `_sum / _count` — mas média não é mediana,
e é justamente a diferença entre as duas que diz se a cauda está puxando o número. Numa população
com uma cauda longa e legítima (Extração de vídeo grande) e uma moda muito baixa (recusa do
ffprobe, ~0,05 s), a média fica num ponto onde não há Extração nenhuma.

**O RED native.** Ele é a terceira tela na lista de Dashboards e responde **"No data"** inteira
sobre um sistema saudável. Quem abre o Grafana numa demo de dez minutos não sabe qual das três é
a morta, e o custo dela é exatamente o do defeito que o 091 perseguiu: uma tela que mente em
silêncio.

## Como fica

- Um terceiro alvo no painel 12, `histogram_quantile(0.5, …)`, sobre os mesmos buckets
  acumulados e com o mesmo corte por `resultado` — a expressão é a das outras duas, com outro
  quantil. O título passa a nomear os três, e a `description` diz como ler p50 **contra** a média
  do painel acima. Os `refId` viram `A`/`B`/`C` na ordem p50, p95, p99, que é a ordem da legenda.
- `docker/observabilidade/grafana-dashboards.yaml`, novo, montado por cima do homônimo da imagem,
  com **dois** providers em vez de três. O JSON dos dois que ficam continua intocado — o que se
  sobrescreve é a lista.
- Guarda no passo 12 do `smoke.sh`: o Grafana lista **exatamente** o painel curado e os dois
  dashboards linkados no topo dele, e nada mais. Ela cobre os dois sentidos do erro, que é o que
  uma lista escolhida à mão passa a admitir — o dashboard morto voltando num upgrade em que
  ninguém rederivou o arquivo, e um dashboard de fábrica **novo** que o override esconderia para
  sempre.

## Critérios de aceite

- [x] O painel 12 desenha p50, p95 e p99 por `resultado`, e a p50 tem amostra não-`NaN`
- [x] O título e a `description` do painel 12 falam dos três quantis, e a `description` diz como ler p50 contra a média
- [x] Numa stack recém-subida, o Grafana lista **três** dashboards: o curado, o *RED classic* e o *JVM Overview*
- [x] Os dois links do topo do painel curado continuam resolvendo
- [x] O passo 12 do `smoke.sh` reprova, visto vermelho de propósito, um Grafana com um quarto dashboard
- [x] O ADR 0004 e o mapa registram a reversão, e o `dashboards.yaml` deixa de dizer que o arquivo da imagem não é tocado
- [x] `scripts/smoke.sh` verde

## Resolução

As duas metades entraram no mesmo commit, e nenhuma linha de Java mudou.

| Arquivo | Mudança |
|---|---|
| `docker/observabilidade/painel-infraestrutura.json` | terceiro alvo no painel 12 (`histogram_quantile(0.5, …)`), título com os três quantis, `description` dizendo como ler p50 contra a média do painel acima; `refId` na ordem p50/p95/p99 |
| `docker/observabilidade/grafana-dashboards.yaml` | **novo**, derivado do homônimo da imagem menos o provider do *RED native*, com a instrução de rederivar no upgrade |
| `docker-compose.yml` | o mount que o sobrepõe, e o porquê de ser o único aqui que sobrescreve arquivo da imagem |
| `docker/observabilidade/dashboards.yaml` | o comentário deixou de dizer que o arquivo da imagem não é tocado |
| `scripts/smoke.sh` | guarda nova no passo 12: o Grafana lista exatamente o painel curado e os dois linkados |
| `docs/adr/0004-camada-de-observabilidade.md` | a tabela dos dashboards de fábrica registra que o terceiro não é mais provisionado, e § *Um painel* registra a p50 |
| `docs/wayfinder/map.md` | linha em *Decisões até aqui* |

### Medido contra o Compose, com o container `observabilidade` recriado

| Verificação | Resultado |
|---|---|
| `GET /api/search?type=dash-db` | **três**: o curado (na pasta *FIAP X*), *RED Metrics (classic histogram)* e *JVM Overview* — sem o native |
| Painel 12 no passo 12 do `smoke.sh` | os três alvos com **4 amostras** não-`NaN` cada, `A` (p50) inclusive |
| Queries do painel | 17, todas com série (eram 16) |
| `scripts/smoke.sh` | **verde** de ponta a ponta |

### A guarda vista vermelha

Com o provider do native reposto de propósito e o container recriado, a guarda reprovou com
*"o Grafana lista 4 dashboards, e não os 3 que este repositório provisiona"*; retirado de novo e
recriado, voltou a passar. Ela conta em vez de procurar o dashboard que sumiu, porque o erro tem
dois sentidos e o segundo — dashboard de fábrica **novo**, escondido pelo override — não tem nome
para procurar.

### O que o `--force-recreate` custa, e por que aqui não importa

Recriar o container zera a base de métrica junto (não há volume, por decisão do 058), então cada
rodada do experimento acima começa sem série. Não atrapalhou: o `smoke.sh` gera o ciclo de que o
passo 12 depende, e a guarda nova pergunta ao Grafana, não ao Prometheus.

## Correção (revisão da mesma sessão)

O `/code-review` nos dois eixos rodou contra a Resolução acima, com o ponto fixo em `3d73a7f`.
Nenhuma violação dura de camada nem defeito de comportamento; seis achados, todos de registro ou
de forma, aplicados no mesmo commit:

- **O comentário novo do `docker-compose.yml` dizia que aquele mount era o único a sobrescrever
  arquivo da imagem, e não é** — o `otelcol-config.yaml` está sete linhas acima e faz o mesmo, e
  o resto deste ticket se apoia justamente nesse precedente. Passou a dizer "o segundo".
- **"Uma lista de quatro"** virou três, no `docker-compose.yml` e no `scripts/smoke.sh`, com o
  número antigo entre parênteses. No 092 a mesma frase é registro histórico e fica.
- **O nome do dashboard.** O cabeçalho do YAML novo usava o nome longo da imagem
  (*exponential/native*), que é sinônimo do que o 091, o ADR e o mapa chamam de *native
  histogram*. Agora usa o curto e cita o literal da imagem entre aspas, porque é ele que aparece
  no arquivo derivado.
- **Duplicação e nome no `smoke.sh`**: o `jq` dos uids linkados era rodado duas vezes e a
  contagem também; viraram `uids_linkados`, `uids_no_grafana`, `uids_do_repositorio`,
  `dashboards_esperados` e `listados`.
- **O ponteiro no 091**, que o `TRACKER.md` prevê para o ticket revertido: uma linha no fim.
- **A abertura do ADR 0004 § *Os três dashboards de fábrica*** ainda dizia que a imagem
  "provisiona três" antes de o leitor chegar à correção. O título fica — o mapa aponta para ele
  pelo nome, e a imagem de fato traz três —, a frase passa a dizer quantos a stack provisiona.

Um acoplamento que a revisão levantou e que fica **de propósito**: a guarda conta
`links + 1`, então o número exigido do Grafana muda junto com os links do topo do painel. É o que
o ticket especifica ("o painel curado e os dois linkados"), e os links já têm guarda própria duas
linhas acima — fixar o número aqui criaria a segunda verdade que o passo 12 evita em toda parte.

`scripts/smoke.sh` rodado de novo depois das edições: verde, com o passo 12 inteiro.

