# Contrato de mensagens

Fronteira real entre `videos`, `extracao` e `notificacao`. Não há módulo Maven `shared`:
cada serviço declara sua própria cópia dos `record`, e **este arquivo é a única fonte de
verdade** que impede as três cópias de divergirem. Alterou aqui, alterou nos três.

Vocabulário em [`CONTEXT.md`](../../CONTEXT.md). Política de falhas em
[ADR 0001](../adr/0001-politica-de-falhas.md). Decidido no
[ticket 007](../wayfinder/tickets/007-contrato-mensagens.md).

## Princípios

- **Comando no imperativo, evento no particípio.** `ExtrairVideo` é uma ordem com um
  destinatário; `ExtracaoConcluida` é um fato consumado.
- **O vídeo nunca trafega.** As mensagens carregam chaves de objeto no MinIO, nunca bytes.
- **Sem envelope.** O tipo da mensagem existe na routing key e na fila, não no corpo.
  Um tipo de mensagem = uma routing key = uma fila = um canal SmallRye = um `record`.
- **`extracao` e `notificacao` nunca se falam.** Toda falha passa por `videos`, porque a
  guarda de unicidade do e-mail é a transição de estado do Vídeo (ADR 0001).
- **Não há garantia de ordem.** Um evento reentregue fora de ordem encontra a transição de
  estado já aplicada e simplesmente não muda nada. Isso é o comportamento correto, não um
  incidente a registrar como erro.

## Fluxo

```
videos ──ExtrairVideo──────────> extracao
videos <─────────ExtracaoIniciada───────── extracao     RECEBIDO   -> PROCESSANDO
videos <─────────ExtracaoConcluida──────── extracao     PROCESSANDO -> CONCLUIDO
videos <─────────ExtracaoFalhou─────────── extracao     PROCESSANDO -> FALHOU
videos ──VideoFalhou──────────> notificacao   (só se a transição mudou a linha)
```

## Topologia

Dois exchanges do tipo `topic`. O nome da fila é prefixado pelo serviço **dono da fila**
(o consumidor), porque é isso que importa no management UI quando algo empilha.

| Exchange | Routing key | Fila | Consumidor |
|---|---|---|---|
| `fiapx.comandos` | `extracao.extrair` | `extracao.extrair` | `extracao` |
| `fiapx.eventos` | `extracao.iniciada` | `videos.extracao-iniciada` | `videos` |
| `fiapx.eventos` | `extracao.concluida` | `videos.extracao-concluida` | `videos` |
| `fiapx.eventos` | `extracao.falhou` | `videos.extracao-falhou` | `videos` |
| `fiapx.eventos` | `video.falhou` | `notificacao.video-falhou` | `notificacao` |

Todas as filas de trabalho são **quorum** com `x-delivery-limit=3` (ADR 0001).

### Dead-letter queues

Assimétricas de propósito: DLQ dedicada onde há consumidor, compartilhada onde é terminal.

| DLQ | Origem | Consumidor |
|---|---|---|
| `extracao.extrair.dlq` | `extracao.extrair` | **`extracao`** — publica `ExtracaoFalhou` com `TENTATIVAS_ESGOTADAS` |
| `videos.dlq` | as três filas de `videos` | nenhum — terminal, intervenção humana |
| `notificacao.dlq` | `notificacao.video-falhou` | nenhum — terminal, intervenção humana |

Numa DLQ compartilhada, a fila de origem só se descobre pelo header `x-death`. É aceitável
porque o destino é olho humano no management UI: mensagem ali significa banco ou SMTP fora
do ar por minutos.

### Quem declara o quê

Cada serviço declara pelo conector SmallRye o que publica e o que consome
(`exchange.declare`, `queue.declare`, `auto-bind-dlq`, `dead-letter-*`). O
`definitions.json` do Compose carrega **apenas** o que não é queue argument: a policy
`dead-letter-strategy=at-least-once` e os usuários.

O motivo é o teste: os Dev Services sobem um broker limpo em `@QuarkusTest` sem o
`definitions.json`. Se a topologia morasse lá, nada rodaria em teste.

### Prefetch

`max-outstanding-messages` é **obrigatório e explícito em todo canal de entrada**. Sem ele
o conector bufferiza até 500.000 mensagens em memória (ticket 003).

| Serviço | Valor | Por quê |
|---|---|---|
| `extracao` | **1** | roda ffmpeg; o ticket 006 mediu até 4,4 GB de PNG numa única Extração |
| `videos` | 20 | escritas curtas em Postgres |
| `notificacao` | 10 | uma chamada SMTP por mensagem |

Isto é configuração, mas está documentado aqui porque é o que decide se um pico derruba o
worker — e o enunciado cobra exatamente "não perder requisições em picos".

## Convenções de tipo

| Conceito | Representação JSON |
|---|---|
| Identificador | UUID em `string` |
| Instante | ISO-8601 em UTC (`Instant`) |
| Tamanho | bytes, número inteiro |
| Chave de objeto | `string`, formato definido pelo `videos` |

## Mensagens

### `ExtrairVideo` — comando, `videos` → `extracao`

| Campo | Tipo | Nota |
|---|---|---|
| `idVideo` | UUID | gerado pelo `videos` no upload; correlaciona todas as mensagens |
| `chaveVideo` | string | onde ler o Vídeo no MinIO |
| `chaveDestinoPacote` | string | onde gravar o Pacote |

O `extracao` **não conhece a convenção de nomes de chave**: recebe origem e destino
prontos. O formato da chave é decisão do `videos` (ticket 011) e mudá-lo não toca este
serviço.

### `ExtracaoIniciada` — evento, `extracao` → `videos`

| Campo | Tipo |
|---|---|
| `idVideo` | UUID |
| `iniciadaEm` | instante |

Existe porque `CONTEXT.md` define que "aguardando na fila" é `RECEBIDO`, não
`PROCESSANDO`. O `videos` não pode marcar `PROCESSANDO` ao publicar o comando — só quando
o worker de fato pegou o trabalho. Numa reentrega o evento chega de novo e a transição
`PROCESSANDO → PROCESSANDO` não faz nada.

### `ExtracaoConcluida` — evento, `extracao` → `videos`

| Campo | Tipo | Nota |
|---|---|---|
| `idVideo` | UUID | |
| `chavePacote` | string | a chave efetivamente gravada |
| `quantidadeFrames` | inteiro | conferida contra a duração do `ffprobe` (ticket 006) |
| `tamanhoBytes` | inteiro | tamanho do `.zip` |
| `concluidaEm` | instante | |

`chavePacote` volta mesmo tendo sido enviada no comando: o evento declara o que foi feito,
em vez de pedir que o `videos` confie na chave que mandou.

### `ExtracaoFalhou` — evento, `extracao` → `videos`

| Campo | Tipo | Nota |
|---|---|---|
| `idVideo` | UUID | |
| `codigoMotivo` | enum | ver tabela abaixo |
| `detalheTecnico` | string | exit code e trecho do stderr; **só para log**, nunca chega ao usuário |
| `ocorridoEm` | instante | |

### `VideoFalhou` — evento, `videos` → `notificacao`

| Campo | Tipo | Nota |
|---|---|---|
| `idVideo` | UUID | |
| `donoSub` | string | `sub` do token; chave de suporte |
| `emailDono` | string | claim `email` do token, persistido no upload |
| `nomeArquivoOriginal` | string | um e-mail que não diz *qual* vídeo falhou é inútil |
| `codigoMotivo` | enum | repassado do `ExtracaoFalhou` |
| `ocorridoEm` | instante | |

Publicado **apenas** pela transição que de fato mudou a linha do Vídeo
(`UPDATE ... WHERE id = ? AND estado = 'PROCESSANDO'`). É essa guarda, e não estado no
`notificacao`, que impede três e-mails.

Consequência para o `notificacao`: ele não tem banco, não tem cliente HTTP e não fala com
o Keycloak. Consome evento, renderiza template, manda SMTP.

Consequência para o realm do Keycloak: o token **precisa** emitir o claim `email`.

Consequência para o `videos`: precisa persistir e-mail do dono e nome do arquivo original
(restrição para o ticket 009).

## Códigos de motivo

Enum estável e pequeno. O texto voltado ao usuário mora no template do `notificacao`, que
é quem tem o contexto de e-mail — não no contrato, e não no `extracao`.

| Código | Quando | Classificação do ticket 006 |
|---|---|---|
| `ARQUIVO_INVALIDO` | não é vídeo, ou está corrompido/truncado | exit 183, `ffprobe` rc≠0, contagem de frames abaixo da duração |
| `FORMATO_NAO_SUPORTADO` | codec ou container que o ffmpeg não decodifica | exit 8 + `Unknown encoder/decoder` no stderr |
| `SEM_FLUXO_DE_VIDEO` | o arquivo abre, mas não tem stream de vídeo | exit 234 + `does not contain any stream` |
| `TENTATIVAS_ESGOTADAS` | `x-delivery-limit` estourou | publicado pelo consumidor da própria DLQ |

O `videos` conhece um sexto valor, `DESCONHECIDO`, que **nenhum serviço publica**: é onde
ele pousa um código que não reconhece, em vez de derrubar a mensagem. É o que torna a tabela
acima extensível sem quebrar consumidor antigo (ticket 009).

`TENTATIVAS_ESGOTADAS` não é enfeite: o consumidor da DLQ **não sabe** por que falhou — só
que a mensagem foi entregue três vezes sem ack. É o único código que ele pode publicar.

## Caminhos de falha

Falha **permanente** e falha **transitória esgotada** convergem no mesmo evento, por
caminhos diferentes:

- **Permanente** (o usuário mandou um `.txt`): o `extracao` publica `ExtracaoFalhou`
  imediatamente e dá **ack**. Não gasta viagem à DLQ para o caso mais comum.
- **Transitória** (MinIO fora, disco cheio, worker morto): `nack`, a mensagem volta à
  fila, o `x-delivery-limit=3` esgota, ela cai em `extracao.extrair.dlq`, e o consumidor
  daquela DLQ publica `ExtracaoFalhou` com `TENTATIVAS_ESGOTADAS`.

São dois sítios de publicação, mas o mesmo use case do `core` é chamado dos dois.

`failure-strategy=fail` está proibido em todos os serviços: derruba o health check e quebra
o `depends_on: service_healthy` do Compose (ADR 0001).

## Camadas

O template não traz exemplo nem regra de arquitetura para mensageria — `framework.dispatcher`
e `core.interfaces.sender` estão documentados no `AGENTS.md` dele, mas vazios. A decisão
deste projeto é **espelhar a borda HTTP**:

| Papel | Onde | Análogo HTTP |
|---|---|---|
| Consumidor (`@Incoming`) | `framework.dispatcher` | `Resource` em `framework.web` |
| Publicador (`@Outgoing` / `Emitter`) | `framework.dispatcher`, implementando uma interface de `core.interfaces.sender` | `DataSourceAdapter` implementando gateway |
| `record` do contrato | `framework.dispatcher` | `Request` do controller |

O consumidor não contém regra: monta o command e chama um controller de
`interfaces.controllers`, que chama o use case. Os `record` deste contrato **nunca cruzam
para o `core`** — o domínio fala em entidades, não em JSON.

Cada serviço acrescenta ao seu `ArchitectureConstraintsTest` a regra de que `@Incoming` e
`@Outgoing` só aparecem em `framework`.

## Versionamento

Há três cópias do contrato. A estratégia é **só aditivo + tolerant reader**:

- Todo consumidor declara `@JsonIgnoreProperties(ignoreUnknown = true)`.
- Campo novo é sempre opcional, e o consumidor antigo o ignora.
- Não existe campo `versao`.
- Mudança **incompatível** (remover campo, mudar tipo, mudar significado) não edita a
  mensagem: cria uma **routing key nova**, e as duas convivem até o consumidor migrar.

Isso custa uma anotação e torna inofensivo subir os três serviços fora de ordem.
