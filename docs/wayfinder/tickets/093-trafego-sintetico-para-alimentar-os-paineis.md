# Tráfego sintético para alimentar os painéis

- id: 093
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-10)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-10. O pedido foi *"quero um script que simule o uso da api para que eu possa
avaliar as métricas com mais dados"*, e ele nasce do [092](092-painel-do-vao-e-a-reversao-parcial-da-recusa.md):
agora existe um painel, e o que o painel mostra numa stack recém-subida é um Vídeo — o do
`smoke.sh`. Avaliar se o painel responde *"a infraestrutura está saudável?"* exige uma
infraestrutura que tenha tido trabalho.

## O que o registro já dizia, e que muda a forma do pedido

Três colisões, as três resolvidas nesta sessão antes de qualquer linha de código.

**`scripts/carga/` é o lugar errado.** Aquele diretório é o harness de experimento, casado com
`docker-compose.carga.yml` — que derruba a stack de observabilidade (`replicas: 0`, ticket 058) e
desliga o SDK dos três serviços (`QUARKUS_OTEL_SDK_DISABLED`, ticket 059). Um gerador de tráfego
cuja finalidade é **alimentar** métrica é o oposto exato desse overlay: ele exige o
`docker-compose.yml` principal, com a stack de pé. Morar ao lado dos scripts que exigem o
contrário é uma armadilha de leitura, e é por isso que este script fica em `scripts/`, vizinho do
`smoke.sh`.

**O `injetor.js` só faz `POST /videos`.** Listagem, consulta, download de Pacote e as rejeições de
borda (400, 404, 409, 415) não existem nele. São justamente o que os dashboards de HTTP que o
[091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md) fez funcionar precisam para
dizer algo além de "uma faixa de 2xx".

**Mais dados não consertam o histograma da duração.** O 092 mediu: os limites de bucket de
`fiapx.extracao.duracao` são os *default* do OpenTelemetry (pensados para milissegundos), a
Extração do `controle-3s` leva 0,19 s, tudo cai no primeiro bucket e `histogram_quantile` devolve
`NaN`. Volume não muda isso. O que este ticket faz é **espalhar a duração** misturando fixtures;
trocar os limites é código novo no `extracao` e é o [094](094-limites-de-bucket-da-duracao-da-extracao.md).

## O que este script é, e o que ele deliberadamente não é

Todo script executável deste repositório **reprova** algo: `smoke.sh` reprova o fluxo
ponta-a-ponta, `concorrencia.sh` reprova a demo que processa um Vídeo por vez, `conservacao.sh`
reprova perda sob falha injetada, `travamento.sh` reprova o estacionamento de réplica. Este é o
primeiro cuja finalidade é **produzir sinal, não julgar** — ele relata e sai 0.

A única falha que ele se permite é a da infraestrutura que o tornaria inútil: stack de
observabilidade ausente ou SDK desligado. Rodar com o overlay de carga ativo produziria zero
métrica **e pareceria funcionar**, que é o tipo de mentira silenciosa que este repositório trata
com guarda.

Critério de correção sob carga continua sendo do `conservacao.sh`. Duplicar portão aqui criaria
duas verdades sobre a mesma pergunta.

## Forma do tráfego

Vinte minutos (default, parametrizável), divididos em blocos de **5 min que alternam**,
começando pela sustentada:

| Bloco | O que faz | Por quê |
|---|---|---|
| sustentada | `constant-arrival-rate`, 6 Vídeo/min | é a linha de base contra a qual o pico significa algo |
| rajada | todos os envios em t=0, resto do bloco drenando | a **drenagem** é o sinal dos painéis de fila: profundidade subindo, não-confirmadas caindo, consumidores ocupados |

Rajada primeiro não tem linha de base. Rajada como "taxa de chegada alta distribuída pelos 5 min"
produz um platô, que é só a sustentada mais rápida.

Em paralelo, pelos 20 min inteiros e em cenários próprios:

- **ciclo de vida** — lista (`GET /videos?estado=…`, paginado), consulta, e baixa o Pacote
  **inteiro** em ~30% dos `CONCLUIDO`. É o que mantém os painéis de HTTP vivos durante a
  drenagem, e "simular uso da API" inclui quem está olhando, não só quem envia. O corpo inteiro
  porque o download é o caminho `RestMulti` de streaming (ticket 016); descartar o corpo mediria
  o cabeçalho. A fração porque baixar sempre põe dezenas de MB por minuto competindo com a
  Extração no mesmo host.
- **erro** — taxa fixa e baixa (~10% do tráfego), cenário **próprio** e não sorteio dentro do
  ciclo: com sorteio a proporção real flutua com a duração de cada ciclo, e deixa de ser
  parâmetro legível.

## Aritmética de capacidade, e por que a sustentada fica abaixo dela

O `map.md` registra **15,6 Vídeo/min** medido com 4 réplicas e teto de 2 CPU; a demo sobe 2
réplicas sem teto. Uma sustentada acima da capacidade acumula backlog que atravessa todos os
blocos e nunca drena — e aí os dois tipos de bloco param de se distinguir no painel, que é a
única coisa que este desenho existe para mostrar. A sustentada fica **abaixo**; só a rajada cria
fila, de propósito.

O script imprime no cabeçalho a demanda estimada contra a capacidade medida, para quem mexer no
parâmetro ver o que está pedindo.

## As quatro classes de erro, e como cada uma nasce

| Status | Como | Por que assim |
|---|---|---|
| `415` | bytes quaisquer com nome `nao-e-video.txt` e content-type `text/plain` | o que a borda julga é content-type e extensão, e nenhum dos dois precisa de arquivo em disco. `invalido.mp4` é inválido de **conteúdo** — vira `FALHOU` pelo ffprobe, não 415 |
| `400` | multipart sem o campo `arquivo` | |
| `404` | `setup()` envia um Vídeo como `outro` e devolve o id; os VUs o pedem como `demo` | custa um envio por corrida e exercita o caminho real. UUID aleatório também dá 404, mas pelo motivo errado — não prova "existe e não é seu", que é a regra do contrato |
| `409` | o VU pede `/pacote` do Vídeo que ele mesmo acabou de enviar | determinístico. Pescar um `RECEBIDO` pela listagem corre o risco de ele concluir entre a listagem e o pedido, e o cenário de erro gera um 200 sem dizer nada |

`410 Gone` fica **fora**: exige expirar o objeto no MinIO, que é injeção de falha.

## Sem injeção de falha, e por que o Estacionamento fica em zero

Nada de `docker kill` aqui. Estacionamento exige esgotar o fundo da DLQ
([`CONTEXT.md`](../../../CONTEXT.md) § *Estacionamento*); num sistema saudável o zero naqueles
dois *stat* do painel **é** a leitura correta. Quem exercita aquele caminho é o `conservacao.sh` e
o modo `mata-publicacao` do ticket 029. Misturar falha aqui estragaria o sinal que o script existe
para produzir.

## Decisões tomadas nesta sessão

| | Decisão |
|---|---|
| Onde mora | `scripts/trafego.sh` + `scripts/trafego.js`, **não** em `scripts/carga/` |
| Compose | o principal, com observabilidade de pé. Guarda aborta se não estiver |
| Papel | relata, nunca reprova (exceto a guarda de infraestrutura) |
| Implementação da alternância | **um** `k6 run`, cenários gerados por `startTime` a partir da duração total — resumo único, mesmo token, blocos exatos; o laço em bash pagaria boot de container a cada 5 min |
| CLI | `scripts/trafego.sh [duracao-em-minutos]`, resto por `FIAPX_*`. Sem modo por tipo de bloco: o valor está na alternância, e blocos isolados reintroduzem o platô recusado |
| Mistura de fixtures | 85% `controle-3s` / 10% `carga-2min` / 5% `invalido.mp4` — espalha a duração e produz `resultado=falhou` |
| Donos | `demo` e `outro`, por `FIAPX_USUARIO`/`FIAPX_SENHA` e `FIAPX_USUARIO_2`/`FIAPX_SENHA_2`, defaults copiados do realm |
| Fim da corrida | drenagem com teto de 10 min; teto estourado **relata** e imprime o que sobrou |
| Censo | `scripts/carga/oraculo.sh censo\|amostra` **intocado**, alimentado pelas linhas `ACEITO <id>` |
| Fixtures | o script chama `gera-fixtures.sh` sozinho |
| Saída | `scripts/saida/trafego-<timestamp>/`, ignorada pelo git |
| Acúmulo | acumula; o reset é `docker compose down -v`. Sem `DELETE` no contrato, e **não se inventa endpoint para script** |
| Guarda do overlay | ambiente dos containers **e** pergunta ao Prometheus, nesta ordem |
| `CONTEXT.md` | não muda — "rajada", "bloco", "tráfego sintético" são vocabulário de instrumento, não de domínio |
| ADR | não cabe: reversível, não surpreendente, e o trade-off que existia já está no ADR 0004 |

## O que não é tocado

`injetor.js`, `oraculo.sh`, `docker-compose.carga.yml` e todo o resto de `scripts/carga/`. O
`injetor.js` em particular é o instrumento que produziu o denominador dos tickets 025–028: mexer
nele quebraria a comparabilidade de qualquer corrida futura daquele overlay. O que o `trafego.js`
reaproveita dele é **cópia consciente** do trato de token e do `k6/experimental/fs`.

## Critérios de aceite

- [x] `scripts/trafego.sh [duracao]` e `scripts/trafego.js`, contra o Compose principal
- [x] Aborta com mensagem acionável se a stack estiver ausente ou o SDK desligado
- [x] Blocos de 5 min alternando sustentada/rajada, começando pela sustentada, num `k6 run` só
- [x] Cenários de ciclo de vida e de erro rodando pela corrida inteira
- [x] As quatro classes de erro saem como especificado; 410 fora
- [x] Mistura de três fixtures; `gera-fixtures.sh` chamado pelo script
- [x] Dois donos, com as quatro variáveis
- [x] Drenagem com teto, censo pelo `oraculo.sh` intocado, saída em `scripts/saida/`
- [x] `.gitignore`, AGENTS.md § *Rodar*, README e `map.md` registram o script e o gatilho dele
- [x] Nada em `scripts/carga/` é modificado
- [x] Validado por uma corrida de 10 min e uma de 20 min, ambas sob `systemd-inhibit`

## Resolução

Feito como especificado. O que o ticket supôs e a medição desmentiu está em *O que as corridas
mudaram no desenho*, abaixo.

### O que mudou

| Arquivo | Mudança |
|---|---|
| `scripts/trafego.js` | **novo**. Injetor k6: os blocos alternados gerados por `startTime`, os cenários `ciclo` e `erro` atravessando a corrida, mistura de três fixtures, dois donos, token por dono e um `handleSummary` com latência por tipo de requisição. Irmão do `injetor.js`, e o cabeçalho diz por que não é uma evolução dele |
| `scripts/trafego.sh` | **novo**. O invólucro: re-executa-se sob `systemd-inhibit`, escreve o terceiro fixture, roda as duas guardas de observabilidade, imprime a demanda contra a capacidade medida, dispara o k6, drena com teto e chama o `oraculo.sh` |
| `.gitignore` | `scripts/saida/` |
| `AGENTS.md` | parágrafo novo na § *Rodar*, com o gatilho e a razão de o script morar fora de `scripts/carga/` |
| `README.md` | duas frases no parágrafo do Grafana: numa stack recém-subida o painel mostra pouco, e o que preenche |
| `docs/wayfinder/map.md` | linha em *Decisões até aqui* |

Nada em `scripts/carga/`, em `docker-compose.carga.yml` ou nos três serviços foi tocado
(`git diff --stat -- scripts/carga docker-compose.carga.yml` vazio).

### O que as corridas mudaram no desenho

**O 409 não é determinístico, e o ticket dizia que era.** A corrida de 10 min mediu 1 em ~40:
a Extração do fixture de controle leva 0,19 s e cabe inteira entre o `202` e o `GET /pacote`
seguinte. Duas consequências, as duas no código: a sonda passou a enviar o fixture de **2 min**,
e um `200` sobre Vídeo já `CONCLUIDO` conta como `CORRIDA` em vez de `INESPERADO` — marcá-lo como
inesperado treinaria quem lê o relatório a ignorar a palavra. A afirmação do corpo deste ticket
fica como está: ela é o que se supôs na época, e a medição é esta seção.

**A amostra acusava o contrato funcionando.** 30% dos envios são do dono `outro` e a
`oraculo.sh amostra` consulta como `demo`: três dos dez sorteados voltaram `404` como
`DIVERGENCIA`. A linha `ACEITO` passou a carregar o dono, e o script mantém **duas** listas — o
censo (Postgres, todos os ids) e a amostra (API, só os do dono consultado).

**Dois defeitos no caminho de relatar, e os dois escondiam em vez de quebrar.** `count` não estava
em `summaryTrendStats`, então `values.count` era `undefined` e **todas** as latências imprimiam
"sem amostra" — a correção que o introduziu tinha sido feita para distinguir "sem amostra" de
`med=0ms`, e trocou um erro pelo outro. E o filtro do dono ancorava em `$`, que não casa nada:
o k6 escreve `msg="ACEITO <id> demo"` e a linha termina em aspas, então a lista da amostra saía
vazia e o passo 5 passava **em silêncio**, imprimindo só o total.

### Como foi verificado

Três corridas contra o Compose principal, sob `systemd-inhibit`: uma de 1 min (ensaio), a de
**10 min** (2 blocos) e a de **20 min** (4 blocos). Na de 20 min, com os defeitos acima
corrigidos:

- 203 Vídeos aceitos, **0 recusados**, 0 respostas inesperadas, 0 corridas do 409;
- 84 erros deliberados, e o `videos` registra as quatro classes no Prometheus — `increase` de 30
  min devolve 202, 200, 404, 400, 415 e 409, que é o que nenhum script antes deste produzia;
- drenou 203/203 em 11 s depois do fim da injeção; censo **194 `CONCLUIDO` e 9 `FALHOU`**;
- latências separadas por tipo: `202` med 357 ms / p(95) 1,65 s, listagem 8 ms (n=601), consulta
  5 ms (n=201), download 29 ms (n=43);
- `fiapx.extracao.duracao` com os **dois** valores de `resultado` povoados — 304 `concluida`
  (média 1,00 s) e 11 `falhou` (média 0,067 s). A média das concluídas subiu de 0,19 s (o número
  do 092, só fixture de controle) para 1,00 s, que é a mistura fazendo o que foi desenhada para
  fazer. Os dois ainda caem no primeiro bucket default — é o [094](094-limites-de-bucket-da-duracao-da-extracao.md);
- `extracao.extrair` chegou a **32** mensagens de profundidade (`max_over_time` de 30 min): a
  rajada criando fila e drenando, que é o sinal que este desenho existe para produzir;
- Estacionamento e DLQs em **0**, que é a leitura correta de um sistema saudável.

As duas guardas foram exercitadas pelas três corridas (stack de pé, SDK ligado, os três serviços
com série no Prometheus). A guarda do overlay não foi exercitada pelo caminho negativo — derrubar
a stack para vê-la reprovar custaria uma subida inteira, e o que ela lê é o ambiente do container,
não um estado de corrida.
