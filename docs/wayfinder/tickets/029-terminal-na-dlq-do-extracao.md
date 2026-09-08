# A falha definitiva do `extracao` tem confirmação e tem fundo

- id: 029
- label: wayfinder:bug
- status: fechado
- assignee: codex
- bloqueado-por:

## Question

`ExtracaoDlqConsumer` é o fundo de toda a recuperação do sistema: quando o `extracao` cai no
meio do ffmpeg, é ele que transforma o `x-delivery-limit=3` esgotado em `ExtracaoFalhou`, e o
próprio Javadoc diz por quê — *"sem ele o Vídeo trava em PROCESSANDO para sempre"*. Ele é o
único consumidor da cadeia **sem terminal próprio**.

A primeira redação deste ticket descreveu o defeito como reenfileiramento infinito: o canal
`extrair-video-dlq` declara `failure-strategy=requeue` e nenhum `queue.x-delivery-limit`, e
`extracao.extrair.dlq` é fila clássica, então uma publicação que falhe de forma persistente
faria a mensagem circular para sempre. **A leitura do conector desmentiu isso**, e o defeito
verdadeiro é pior.

`publish-confirms` é `false` por default no conector, e o `extracao` **não o liga em nenhum
dos seus três canais de saída**. O [027](027-melhorias-medidas.md) ligou confirms, mas só nos
dois canais do `videos` (`videos/src/main/resources/application.properties:93,99`). Sem
confirms, `emitterFalhou.send(...).subscribeAsCompletionStage()` completa quando o byte sai no
socket, não quando o broker aceita: se o broker recusar a publicação — exchange ausente,
`x-max-length` estourado, qualquer erro de canal —, o `send` **completa com sucesso**, o
consumidor da DLQ dá **ack**, e a mensagem some. Não há circulação nem reentrega; há **perda
silenciosa**, e o Vídeo fica em `PROCESSANDO` do mesmo jeito.

O loop descrito na primeira redação existe, mas só para o que falha *antes* do socket —
serialização, `NullPointerException` no adapter. É o caminho estreito; a perda silenciosa é o
largo. E as duas convergem no mesmo lugar: **nenhuma varredura alcança o Vídeo**, porque o
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) republica apenas o que nunca saiu de
lá (`comando_publicado_em is null`) e recusou por escrito a varredura por idade. É o mesmo
"Vídeo preso para sempre" que o [027](027-melhorias-medidas.md) mediu em 11/400, por outro
caminho — e este caminho não tem instrumento que o revele.

Daí o escopo: ligar `publish-confirms` é o que **cria** o loop que este ticket precisa limitar.
Fazer só uma das duas metades produziria ou uma rede de segurança para um caminho quase
inalcançável, ou um erro que passa a ser visto e a circular sem fundo. As duas juntas, ou
nenhuma.

## O que fica decidido

O alvo é **falha permanente de publicação**. Indisponibilidade transitória não é hazard aqui:
se o broker está fora, o canal de *consumo* da DLQ está fora junto, não há reentrega em loop, e
o `retry-on-fail-attempts=6 / retry-on-fail-interval=5s` do publicador já absorve o blip antes
de o `send` falhar. Os 30 s desse retry são a única retentativa real do caminho — o que vier
depois dela é determinístico e não melhora repetindo.

### A topologia

Um salto a mais, todo em configuração, sem fila nova no `definitions.json`:

```
extracao.extrair ──x-delivery-limit=3──> extracao.extrair.dlq ──reject──> extracao.extrair.estacionamento
                                         (consumidor publica              (sem consumidor,
                                          ExtracaoFalhou)                  olho humano)
```

No canal de origem `extrair-video`, que já declara a DLQ pelo `auto-bind-dlq`:

```properties
mp.messaging.incoming.extrair-video.dead-letter-queue-type=quorum
mp.messaging.incoming.extrair-video.dead-letter-dlx=extracao.dlx
mp.messaging.incoming.extrair-video.dead-letter-dlx-routing-key=extracao.extrair.estacionado
```

No canal `extrair-video-dlq`, que passa a ter DLQ própria:

```properties
mp.messaging.incoming.extrair-video-dlq.failure-strategy=reject
mp.messaging.incoming.extrair-video-dlq.queue.x-queue-type=quorum
mp.messaging.incoming.extrair-video-dlq.auto-bind-dlq=true
mp.messaging.incoming.extrair-video-dlq.dlx.declare=true
mp.messaging.incoming.extrair-video-dlq.dead-letter-exchange=extracao.dlx
mp.messaging.incoming.extrair-video-dlq.dead-letter-routing-key=extracao.extrair.estacionado
mp.messaging.incoming.extrair-video-dlq.dead-letter-queue-name=extracao.extrair.estacionamento
mp.messaging.incoming.extrair-video-dlq.dead-letter-queue-type=quorum
```

E `publish-confirms=true` nos três canais de saída (`extracao-iniciada`, `extracao-concluida`,
`extracao-falhou`).

Quatro propriedades desse desenho não são óbvias e precisam sobreviver como comentário no
`application.properties`:

- **O conector declara e liga a fila de estacionamento sozinho.**
  `RabbitMQClientHelper.configureDLQorDLX` termina em `queueDeclare` + `queueBind`, então
  `auto-bind-dlq` no canal da DLQ cria a `extracao.extrair.estacionamento` e a liga à
  `extracao.dlx` na chave `extracao.extrair.estacionado`. O `definitions.json` continua só com
  policies e usuários, e — o que importa mais — **a rede de segurança existe nos Dev Services**,
  logo é testável em `@QuarkusTest`. Uma fila declarada fora dos canais não seria.
- **`extracao.dlx` é reusada**, não há exchange nova. Ela já é declarada pelo `dlx.declare` do
  canal de origem, é `direct` (o default de `dead-letter-exchange-type` nos dois lados), e a
  redeclaração é idempotente.
- **Os dois canais continuam declarando a `extracao.extrair.dlq`, e os argumentos batem.** É a
  propriedade que o comentário atual do arquivo defende, e ela sobrevive: os dois escrevem
  `x-queue-type=quorum`, `x-dead-letter-exchange=extracao.dlx` e
  `x-dead-letter-routing-key=extracao.extrair.estacionado`, cada um pelo seu conjunto de
  atributos. Nada de ordem de boot, nada de `queue.declare=false`.
- **Não há `x-delivery-limit` na DLQ, e é deliberado.** Com `reject` (que é o *default* do
  conector, sobrescrito para `requeue` em todos os canais deste projeto), o nack vai com
  `requeue=false` e a mensagem cai no DLX **na primeira falha** — um salto, não vinte. O limite
  de 20 que o RabbitMQ 4.x aplica por default a fila quorum vira **segunda** linha de defesa,
  que nunca dispara. Quem for remover o quorum achando que ele não faz nada deve ler o
  parágrafo seguinte antes.

O quorum na DLQ não é enfeite: o [ADR 0001](../../adr/0001-politica-de-falhas.md) recusou filas
clássicas porque o dead-lettering delas é *at-most-once*, e podem perder exatamente a mensagem
de falha definitiva. O salto para o estacionamento carrega essa mensagem. Manter a DLQ clássica
pouparia a migração da seção abaixo e reproduziria, um nível mais fundo, a decisão que o ADR já
tomou um nível acima.

### O Vídeo continua preso, e isso é aceite explícito

A fila de estacionamento é fim de linha: sem consumidor, inspecionada por operação no
management UI, igual à `videos.dlq` e à `notificacao.dlq`. O Vídeo correspondente segue em
`PROCESSANDO` e **o Dono não recebe e-mail** — a promessa do enunciado fica quebrada nesse
caminho. O que muda é que o preso passa a ser **visível**, com nome, fila e profundidade, em vez
de invisível. Essa é a diferença que este ticket compra, e ela não deve ser vendida como outra.

Alcançar o desfecho por outro caminho — varredura de `PROCESSANDO` por idade — está fora deste
ticket porque o ADR 0003 a recusou por escrito. Reabrir aquela recusa é ticket de arquitetura,
não de configuração, e o [033](033-iniciada-em-morto.md) é onde a coluna que ela exigiria já
está sendo discutida.

### Opções recusadas, com o motivo

- **`failure-strategy=fail` no canal da DLQ** — proibida pelo ADR 0001 e por
  `docs/contratos/mensagens.md` § *Caminhos de falha*: derruba o health check e quebra o
  `depends_on: service_healthy` do Compose. A primeira redação deste ticket a listou como opção
  real sem consultar o ADR.
- **Distinguir falha de publicação de falha de processamento** — não se aplica: o
  `ExtracaoDlqConsumer` só publica. Não há processamento do qual distinguir.
- **`x-delivery-limit` na DLQ mantendo fila clássica** — pouparia a migração, e o RabbitMQ 4.x
  *talvez* aceite limite de entregas em fila clássica (o `@ConnectorAttribute` do conector
  condiciona o atributo a quorum, e `docs/pesquisa/rabbitmq-retry-dlq.md` foi escrito contra o
  3.12, onde era exclusivo de quorum). **Não foi medido** — e com `reject` o contador deixa de
  ser o mecanismo, então a aposta não compraria nada além do at-most-once que o ADR 0001 recusa.
- **Declarar a fila de estacionamento no `definitions.json`** — funcionaria em produção e
  **não** nos Dev Services, que sobem um broker limpo sem ele. Deixaria a rede de segurança sem
  cobertura em `@QuarkusTest`, com a rodada de carga como única prova. Desnecessária depois que
  o `auto-bind-dlq` no canal da DLQ mostrou fazer o trabalho.
- **`dead-letter-queue.arguments` para um limite explícito** — é o identificador de um bean CDI
  `Map<String,?>` (`RabbitMQClientHelper.java:259-265`), ou seja, **código**, para escrever um
  número que `reject` torna irrelevante.

## Migração: a fila precisa ser apagada

`x-queue-type` é argumento de declaração. Numa `extracao.extrair.dlq` já existente como
clássica, a nova declaração leva **406 PRECONDITION_FAILED** e o `extracao` não sobe. O
procedimento é apagar a fila — management UI ou volume limpo do RabbitMQ — **antes** de subir a
versão nova, e ele descarta o que estiver nela. Numa demo com DLQ vazia isso é gratuito; em
qualquer outro lugar, drenar primeiro.

Renomear a fila evitaria o passo manual e foi recusado: deixaria uma `extracao.extrair.dlq`
órfã com mensagens reais dentro e ninguém olhando, que é literalmente o defeito que este ticket
existe para remover.

## Condição de aceite

Em duas partes, porque uma roda no CI e a outra não.

**1. `@QuarkusTest` de topologia.** Com a fila de estacionamento declarada pelo conector, ela
existe no Dev Service: um teste que faz o consumidor da DLQ falhar e afirma que a mensagem
chega à `extracao.extrair.estacionamento` impede a topologia de regredir em silêncio, e roda a
cada `./mvnw test`.

**2. Medição de ponta a ponta, não raciocínio.** Um quarto modo no
[`scripts/carga/conservacao.sh`](../../../scripts/carga/conservacao.sh) — `mata-publicacao` —
que sobe o `extracao` com `exchange.declare=false` no canal `extracao-falhou` apontando para
uma exchange inexistente, e mostra a mensagem parando no estacionamento em vez de circular ou
sumir.

O modo `mata-videos` do [025](025-carga-conservacao.md), que a primeira redação apontou como
instrumento mais próximo, **não serve**: derrubar o `videos` não faz a publicação do `extracao`
falhar — o `ExtracaoFalhou` vai para `fiapx.eventos`, que aceita a mensagem com o `videos` de pé
ou não. Ele exercita a varredura do ADR 0003, que é outra pergunta. E a repro que a primeira
redação sugeria não funciona sozinha: `exchange.declare` é `true` por default, então o canal
declara a exchange "inexistente" no boot e a publicação passa. Precisa de `exchange.declare=false`
**e** de `publish-confirms=true` para o erro sequer chegar ao JVM.

Este modo é a única parte do ticket que é construção, não configuração — o que obriga uma
variante de config do `extracao` no overlay de carga.

## O que muda fora do `extracao`

- **`ExtracaoDlqConsumer`** ganha um `WARN` com o `idVideo` antes de propagar a falha. O log do
  conector (`log.nackedIgnoreMessage`) diz que *um* nack aconteceu no canal; o que operação
  precisa para agir é *qual Vídeo* ficou preso, e esse número está na mensagem. É a única linha
  de código do serviço neste ticket. Nada de métrica ou health check novo: a fila tem
  profundidade e o management UI a mostra.
- **[ADR 0001](../../adr/0001-politica-de-falhas.md)** recebe emenda, no estilo do 027 sobre o
  ADR 0002: o bullet *"a DLQ do `extracao` tem consumidor; a do `videos` e a do `notificacao`
  não... as outras duas são terminais"* passa a registrar que o consumidor da DLQ é ele próprio
  um publicador, e que por isso aquela DLQ **deixa de ser terminal** e ganha fundo próprio.
- **`docs/contratos/mensagens.md` § Dead-letter queues** ganha a terceira linha na tabela e
  perde a descrição da `extracao.extrair.dlq` como terminal.
- **`CONTEXT.md`** ganha **Estacionamento**: mensagem que esgotou o próprio fundo e cujo desfecho
  só um humano produz. O glossário hoje define terminal para o *Vídeo* (`CONCLUIDO`, `FALHOU`) e
  não tem palavra para terminal de *fila* — a mesma palavra vinha significando as duas coisas em
  dois documentos.

## Por que este é o primeiro da fila

Protege o mecanismo do qual todos os outros tickets desta rodada dependem. Fazer o
[031](031-decisao-de-transicao-em-java.md) antes seria melhorar a leitura de uma regra cuja rede
de segurança tem um buraco — e, agora que se sabe que o buraco é perda silenciosa e não loop, um
buraco que nenhuma medição existente detectaria.

## Resolução

A topologia decidida acima está no `application.properties` do `extracao`: o canal
`extrair-video-dlq` tem `failure-strategy=reject` e DLQ própria, e o `reject` leva o comando à
`extracao.extrair.estacionamento` — fila quorum, sem consumidor. O `ExtracaoDlqConsumer` loga o
`idVideo` antes de propagar a falha, para que operação saiba *qual* Vídeo parou. Fora do
`extracao`: o ADR 0001 registra que a DLQ do `extracao` deixou de ser terminal e ganhou fundo
próprio, `docs/contratos/mensagens.md` § Dead-letter queues ganhou a terceira linha, e o
`CONTEXT.md` ganhou o verbete **Estacionamento**, separando terminal de fila de terminal de
Vídeo.

Das duas partes do aceite, a primeira fechou aqui: `ExtracaoEstacionamentoTest` prova a
topologia contra RabbitMQ real e roda a cada `./mvnw test`. Ele reprovou por um defeito que não
era de topologia — publicação em exchange inexistente fecha o canal e deixa a promessa de
confirmação pendente para sempre —, e essa correção virou o
[037](037-estacionamento-nao-recebe-falha-da-dlq.md). A segunda parte, o modo `mata-publicacao`
do `conservacao.sh`, só conseguiu subir depois do
[038](038-override-de-canal-por-variavel-quebra-o-boot.md), e chegou ao veredito com o critério
do estacionamento **reprovado** por prazo: zero mensagens novas em 241 s, limite de 240 s. A
garantia deste ticket, portanto, está construída e provada em teste, mas **não confirmada sob
carga**; o 038 registra o número e o diagnóstico pendente.

## Reabertura (075)

O [075](075-confirmar-estacionamento-sob-carga.md) remediu `mata-publicacao` contra o HEAD
atual e reproduziu a mesma reprovação (0/3 no estacionamento, 241 s, limite 240 s) — e desta
vez com diagnóstico. A causa é **circulação**: para uma falha de extração já classificada como
permanente (`FalhaPermanenteDeExtracaoException`), `ProcessarExtracaoUseCase.tratarFalha`
devolve direto o futuro de `enviarFalhou(...)`; se essa publicação falhar — o defeito que este
próprio ticket injeta para testar —, a falha sobe como se fosse transitória e
`ExtrairVideoConsumer` reenfileira o comando no canal `extrair-video`, mandando o ffprobe rodar
de novo sobre o mesmo arquivo. O `x-delivery-limit=3` da fila `extracao.extrair`, que deveria
limitar esse loop e escalar ao DLQ (e daí ao estacionamento, pelo desenho deste ticket), não
dispara: os headers da mensagem em voo mostraram `x-acquired-count` de 24-25 contra
`x-delivery-count` de 1-2 — o contador que a fila usa para decidir quando esgotar não acompanha
as tentativas reais. A mensagem nunca sai de `extracao.extrair`, e por isso nunca chega a
`extracao.extrair.dlq` nem a `extracao.extrair.estacionamento`.

A garantia que este ticket declara — "falha definitiva para no estacionamento" — **não vale**
para o caminho de falha permanente detectada de imediato (a maioria dos casos reais: formato
inválido, é o que o `mata-publicacao` exercita). Ela só foi provada em teste
(`ExtracaoEstacionamentoTest`) porque aquele teste força o esgotamento via
`x-delivery-limit` diretamente, sem passar pelo caminho de falha permanente do
`ProcessarExtracaoUseCase`. Evidência completa em
`scripts/carga/saida/075-mata-publicacao/`.

## Resolução da reabertura

A circulação foi cortada no ponto em que a classificação se perdia. Quando uma Extração já
falhou permanentemente e a publicação de `ExtracaoFalhou` também falha,
`ProcessarExtracaoUseCase` agora propaga `FalhaAoPublicarExtracaoFalhouException`, preservando
que o trabalho já tem desfecho definitivo. `ExtrairVideoConsumer` reconhece essa causa e envia
o nack com `RabbitMQRejectMetadata(false)`: o comando não volta a executar ffprobe/ffmpeg, segue
direto para `extracao.extrair.dlq` e, se a publicação continuar indisponível, o consumidor da
DLQ o rejeita para `extracao.extrair.estacionamento`. Falhas transitórias continuam usando o
nack sem metadado e o `failure-strategy=requeue` anterior.

O `AckManual` ganhou uma sobrecarga que aceita os metadados do nack; as três cópias deliberadas
foram atualizadas juntas e continuam iguais fora da linha de pacote. A prova foi feita em três
níveis: teste do use case para a preservação da classificação, teste do consumidor para
`requeue=false` e `ExtracaoEstacionamentoTest` pela borda AMQP real, publicando em
`fiapx.comandos` e observando o corpo original no Estacionamento. Esta última passou com
RabbitMQ e MinIO reais em 92,94 s (dois cenários); como o host não tem ffprobe, a execução usou
um executável determinístico que retorna exit code 1, ainda atravessando o adapter real e sua
classificação de falha permanente.
