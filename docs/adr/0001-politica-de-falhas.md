# Política de falhas: quorum, retry híbrido e unicidade no dono do estado

A extração de vídeo é assíncrona e pode falhar; o enunciado exige que o sistema não perca
requisições em picos e que o usuário seja notificado quando a extração do seu vídeo falha
definitivamente. A pesquisa do ticket
[003](../wayfinder/tickets/003-rabbitmq-retry-dlq.md) mostrou que a stack não oferece
"3 tentativas com backoff que sobrevivam a crash" como mecanismo único, e que "nunca perder
a falha" e "nunca duplicar o e-mail" são objetivos que se contradizem. Decidimos: filas
**quorum** com `x-delivery-limit=3` como contagem durável de entregas, `@Retry` com backoff
de segundos nos *adapters* de I/O para absorver indisponibilidade transitória, e a
**unicidade da notificação ancorada na transição de estado do Vídeo em `videos`**, não em
estado próprio do `notificacao`.

Emendado no [ticket 029](../wayfinder/tickets/029-terminal-na-dlq-do-extracao.md): o
consumidor da DLQ do `extracao` é ele próprio um publicador, e um publicador sem
`publish-confirms` pode achar que publicou quando o broker recusou — nesse caso ele dá
**ack** e a falha definitiva some em silêncio, sem passar pela DLQ e sem que nenhuma
varredura a alcance. Por isso aquela DLQ deixa de ser terminal e ganha fundo próprio, a
`extracao.extrair.estacionamento` — ver *A DLQ do `extracao` tem consumidor* nas
Consequences, abaixo, e o ticket 029 para o desenho completo.

Emendado na reabertura do mesmo ticket: uma falha permanente só termina em **ack** quando
`ExtracaoFalhou` foi aceito pelo broker. Se essa publicação falhar, o use case preserva que a
Extração já tem desfecho definitivo e o consumidor rejeita o comando original com
`requeue=false`. Ele salta diretamente para `extracao.extrair.dlq`; se a publicação continuar
indisponível ali, o consumidor da DLQ o rejeita para `extracao.extrair.estacionamento`.
Reexecutar ffprobe/ffmpeg não pode consertar o canal de saída e só faria o comando circular.
Falhas transitórias do trabalho continuam seguindo o nack com requeue e o
`x-delivery-limit=3`.

Emendado de novo no [ticket 061](../wayfinder/tickets/061-travamento-raro-com-o-sdk-desligado.md):
a retentativa nos *adapters* de I/O continua sendo a mesma política — três tentativas (leia
*três chamadas ao recurso*: a palavra e a aritmética são as da emenda do 086, abaixo), espera
de 2 s, só sobre `Exception` —, mas **deixou de ser o `@Retry`**. O interceptor do
MicroProfile Fault Tolerance, numa operação verdadeiramente assíncrona, reagenda a chamada no
contexto Vert.x do chamador; quando esse contexto só é liberado depois da própria chamada, o
reagendamento nunca roda e a Extração trava para sempre, sem thread, sem log e sem ack. Foi
medido: 4 travamentos em ~60 ciclos com o interceptor, 0 em 90 sem ele. Onde este ADR diz
`@Retry`, leia `onFailure().retry()` do Mutiny — e o `ArchitectureConstraintsTest` agora
reprova o build que traga o interceptor de volta.

Emendado de novo no
[ticket 086](../wayfinder/tickets/086-contagem-de-repeticoes-em-dois-numeros.md), que é
aritmética e vocabulário, e não mudança de política. Onde a emenda acima diz "três
tentativas", leia **três chamadas ao recurso: a primeira mais duas repetições**. A palavra
*tentativa* volta a ter aqui o sentido único que o [`CONTEXT.md`](../../CONTEXT.md) lhe dá —
uma *entrega* do trabalho ao serviço `extracao` —, e a repetição de I/O dentro de uma dessas
entregas passa a se chamar **repetição**.

A aritmética é uma só e mora nesta seção: `atMost(n)` do Mutiny conta as repetições *depois*
da primeira chamada, então três chamadas se escrevem `atMost(2)`. Ela vale nos quatro lugares
que implementam esta política, e cada um cita esta frase em vez de recontar: as três cópias de
`comRepeticao` — `videos` e `extracao` no MinIO, `notificacao` no SMTP — e o
`RepeticaoNoPostgres` do `videos`, que se chamava `PostgresRetry` até o
[ticket 087](../wayfinder/tickets/087-postgresretry-diverge-das-copias-de-comrepeticao.md).

**Por que três chamadas, e não quatro.** O número multiplica a espera de 2 s, e é ele que
decide por quanto tempo um recurso que não volta segura quem o chamou: quatro chamadas
prendem por 6 s antes de devolver a falha, três por 4 s. Atrás da borda HTTP do `videos` esses
segundos são o cliente esperando um `500` que já está decidido; nos dois workers são a réplica
com `max-outstanding-messages=1`, que não consome mais nada enquanto espera. Do outro lado, a
quarta chamada só compra o blip que durou mais que duas esperas — e um blip de mais de 4 s já
não é o que esta política tenta absorver: o que segura a indisponibilidade mais longa é o
`x-delivery-limit=3` da fila, que reentrega o trabalho inteiro depois, e não uma repetição a
mais dentro do adapter.

Os dois números existiam porque as duas leituras estavam implementadas: as três cópias de
`comRepeticao` faziam quatro chamadas, herdadas número por número do `@Retry(maxRetries=3)`
que o ticket 061 removeu, enquanto o `RepeticaoNoPostgres` já fazia três — o
[ticket 057](../wayfinder/tickets/057-retry-transitorio-no-postgres.md) o levara de quatro a
três lendo "três tentativas" como três chamadas. A escolha de agora é a do 057, aplicada aos
quatro; o que muda no código são as três cópias de `comRepeticao`, que passam a `atMost(2)`.

## Considered Options

**Backoff durável via TTL + dead-letter-exchange manual** foi rejeitado. É o único caminho
que dá contagem durável *e* espera entre tentativas, mas custa cerca de um dia de topologia
à mão. O que falha de forma transitória aqui são blips de I/O de segundos (MinIO, Postgres),
que o `@Retry` no adapter cobre; o que falha por memória ou disco numa extração de 4,4 GB
falha de novo daqui a 30 minutos, porque o worker tem tamanho fixo. Backoff longo adia sem
curar.

**Filas classic** foram rejeitadas porque o dead-lettering é *at-most-once*: podem perder
exatamente a mensagem de falha definitiva, deixando o Vídeo eternamente em `PROCESSANDO` e
o usuário sem e-mail. Escolhemos não perder e tratar a duplicata, e não o contrário.

**Estado de deduplicação em `notificacao`** foi rejeitado. Manteria o e-mail exatamente-uma-
vez, mas daria banco ao mais fino dos três serviços. Em vez disso, `videos` — que já tem
estado transacional — usa `UPDATE ... WHERE id = ? AND estado = ?` como guarda: só a
atualização que de fato mudou a linha publica o evento. Isso não é uma regra de entrega
dentro do dono do estado; é o invariante "um Vídeo cai para `FALHOU` uma única vez", do qual
o e-mail é consequência.

Onde esse `UPDATE` se encaixa nas camadas — e por que o grafo de estados continua no `core`
apesar dele — está no [ADR 0002](0002-maquina-de-estados-em-duas-camadas.md).

Esse `UPDATE` e o `publish` que o segue, porém, não são atômicos entre si — nem o são o
`INSERT` do upload e a publicação de `ExtrairVideo`. Como essas duas janelas são fechadas
sem transactional outbox está no
[ADR 0003](0003-reconciliacao-por-varredura.md).

## Consequences

- **"Tentativa" passa a significar "entrega", não "erro".** Fila quorum conta reentregas, e
  um crash do worker consome uma delas sem que nada tenha dado errado. É desejável: um vídeo
  que derruba o processo três vezes é *poison message*. Mas muda o vocabulário.
  Desde o ticket 086, este ADR não gasta mais a mesma palavra na outra contagem: a ida repetida
  ao MinIO, ao SMTP ou ao Postgres dentro de uma entrega é **repetição**, e o que se conta ao
  fim é **chamada ao recurso**.
- **O e-mail é "pelo menos uma vez", não "exatamente uma vez".** Se o `notificacao` morre
  entre o retorno do SMTP e o ack, a mensagem é reentregue e o e-mail sai de novo. A janela
  é de milissegundos e só em caso de crash — é o preço de `notificacao` sem banco.
- **A policy `dead-letter-strategy=at-least-once` só existe no Compose.** Ela é policy de
  broker, não queue argument, e os Dev Services só declaram topologia via *arguments*. Teste
  roda `at-most-once`; a diferença só se manifesta em failover, que o `@QuarkusTest` não
  exercita. Com broker de nó único, o que de fato carrega peso na escolha de quorum é o
  `x-delivery-limit`, não o `at-least-once`.

  Emendado no
  [ticket 103](../wayfinder/tickets/103-dead-lettering-at-least-once-sem-reject-publish.md):
  até ali, **nem no Compose** o regime era `at-least-once`. A policy definia só a estratégia, e
  a documentação de quorum queues do RabbitMQ exige também `overflow=reject-publish`: com o
  `drop-head` default, *"even if a queue length limit is not set"*, o dead-lettering volta a
  `at-most-once`. A pesquisa do ticket 003 já registrava o par; o `definitions.json` levou só
  metade. A policy `dead-letter-at-least-once` passou a carregar os dois campos. Policy é
  dinâmica, então não é preciso recriar fila, e as sete filas quorum trazem os dois campos na
  política efetiva. A verificação está no ticket. As duas DLQs classic não recebem a policy.
  Medido no RabbitMQ 4.3: uma policy com chave que a classic não suporta, como
  `dead-letter-strategy`, deixa de casar com ela **inteira**. Sem limite de tamanho, isso não
  muda nada, porque o regime de dead-lettering é da fila de origem.

  Onde o parágrafo acima diz que a diferença "só se manifesta em failover", leia: **também em nó
  único**. Mensagem dead-lettered fica viva na fila de origem até a DLQ confirmar. Se a DLX não
  existe, se a mensagem não tem rota ou se o destino não confirma, ela fica retida e é
  retentada, e pode chegar duplicada à DLQ. Em vez de sumir, ela ocupa a fila de origem. É o
  preço esperado de `at-least-once`, e o *"pelo menos uma vez"* do e-mail acima já o absorve.

  Há mais dois efeitos colaterais. **`reject-publish` recusa publicação com a fila cheia**, em
  vez de descartar a mais antiga. Hoje não dispara, porque nenhuma fila tem `x-max-length` nem
  `x-max-length-bytes`, nem por argumento nem por policy. Quem puser limite de tamanho passa a
  receber nack no publicador, que o `publish-confirms` obrigatório já trata. O outro efeito:
  **sair do regime descarta o que está retido**. A documentação diz que trocar a estratégia para
  `at-most-once`, ou `overflow` para `drop-head`, apaga as mensagens dead-lettered ainda sem
  confirmação. Tirar qualquer dos dois campos da policy, inclusive por edição do
  `definitions.json`, é perda, e não reconfiguração inócua.
- **A DLQ do `extracao` tem consumidor; a do `videos` e a do `notificacao` não — e por
  isso ela própria deixou de ser terminal (ticket 029).** O `extracao` consome a própria DLQ
  e publica a falha definitiva — sem isso, nada reage à DLQ e o Vídeo trava em
  `PROCESSANDO`. Mas esse consumidor é ele mesmo um publicador, sujeito ao mesmo risco de
  perda silenciosa de qualquer publicação sem `publish-confirms`: sem confirmação, o broker
  pode recusar a publicação e o consumidor dá ack do mesmo jeito. A `extracao.extrair.dlq`
  ganhou fundo próprio, a `extracao.extrair.estacionamento`, para esse caso — ela é terminal
  no lugar da DLQ. `videos.dlq` e `notificacao.dlq` continuam terminais como antes: mensagem
  ali significa banco ou SMTP fora por minutos, que é intervenção humana pelo management UI.
- **Falha ao publicar uma falha permanente não é falha transitória da Extração.** O comando
  original recebe nack com `requeue=false` e vai direto à DLQ, sem gastar novas execuções de
  ffprobe/ffmpeg. O consumidor da DLQ ainda tenta publicar `ExtracaoFalhou` e, se o broker
  continuar recusando, entrega o comando ao Estacionamento. O nack comum permanece com
  requeue para falhas transitórias reais do trabalho.
- **`failure-strategy=fail` está fora** em todos os serviços: derruba o health check e
  quebra o `depends_on: service_healthy` do Compose.
