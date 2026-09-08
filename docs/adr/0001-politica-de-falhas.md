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
a retentativa nos *adapters* de I/O continua sendo a mesma política — três tentativas, espera
de 2 s, só sobre `Exception` —, mas **deixou de ser o `@Retry`**. O interceptor do
MicroProfile Fault Tolerance, numa operação verdadeiramente assíncrona, reagenda a chamada no
contexto Vert.x do chamador; quando esse contexto só é liberado depois da própria chamada, o
reagendamento nunca roda e a Extração trava para sempre, sem thread, sem log e sem ack. Foi
medido: 4 travamentos em ~60 ciclos com o interceptor, 0 em 90 sem ele. Onde este ADR diz
`@Retry`, leia `onFailure().retry()` do Mutiny — e o `ArchitectureConstraintsTest` agora
reprova o build que traga o interceptor de volta.

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
- **O e-mail é "pelo menos uma vez", não "exatamente uma vez".** Se o `notificacao` morre
  entre o retorno do SMTP e o ack, a mensagem é reentregue e o e-mail sai de novo. A janela
  é de milissegundos e só em caso de crash — é o preço de `notificacao` sem banco.
- **A policy `dead-letter-strategy=at-least-once` só existe no Compose.** Ela é policy de
  broker, não queue argument, e os Dev Services só declaram topologia via *arguments*. Teste
  roda `at-most-once`; a diferença só se manifesta em failover, que o `@QuarkusTest` não
  exercita. Com broker de nó único, o que de fato carrega peso na escolha de quorum é o
  `x-delivery-limit`, não o `at-least-once`.
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
