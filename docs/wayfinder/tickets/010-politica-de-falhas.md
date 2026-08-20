# Política de falhas: retry durável, dead-letter e unicidade da notificação

- id: 010
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep (sessao de 2026-08-20)
- bloqueado-por:

## Question

A cartografia fixou: "retry 3x com backoff para falhas transitórias, falha permanente não
gasta retry, esgotado vai para DLQ, vídeo vira `FALHOU`, e-mail sai uma vez". A pesquisa do
ticket 003 mostrou que **essa política não existe pronta na stack** — e que duas garantias
dela se contradizem entre si. Precisa ser redecidida com os fatos na mesa.

A decidir:

- **Qual dos três caminhos de retry**, sabendo que são mutuamente excludentes:
  (a) `@Retry` + `@ExponentialBackoff` da Fault Tolerance — tem backoff, é in-process,
      **não sobrevive a crash do worker**;
  (b) fila **quorum** com `x-delivery-limit` — contagem durável, **sem backoff**, e o
      default de 20 não existe na imagem 3.12 dos Dev Services;
  (c) **TTL + dead-letter-exchange manual** — completo, e custa cerca de um dia de
      topologia à mão.
  O backoff vale o preço aqui? O que de fato falha de forma transitória neste sistema
  (MinIO fora, memória) e em que janela de tempo se recupera?
- **Classic ou quorum, dado o trade-off**: classic dead-letta at-most-once e pode **perder a
  falha definitiva** (o usuário nunca é notificado); quorum com `at-least-once` não perde
  mas **duplica** (o usuário recebe dois e-mails). Qual das duas falhas é menos ruim para
  este produto?
- **Onde mora a guarda de unicidade do e-mail.** A sugestão da pesquisa é usar a transição
  `PROCESSANDO → FALHOU` em `videos` como ponto de unicidade — só a primeira transição
  publica o evento que o `notificacao` consome. Isso preserva "só `videos` tem banco", mas
  coloca uma regra de entrega dentro do dono do estado. É o lugar certo?
- **Prefetch**: `max-outstanding-messages` no `extracao` (o default bufferiza até 500.000
  mensagens). 1 ou 2? Como isso interage com "processar mais de um vídeo ao mesmo tempo",
  que passa a depender de réplicas, não de concorrência intra-processo?
- **`failure-strategy`**: `fail` derruba o health check e quebra o Compose. Qual estratégia,
  então, e o que acontece com a mensagem que a aciona?
- **Versão do RabbitMQ** no Compose e nos Dev Services — fixar 4.x muda os defaults de
  `x-delivery-limit` e é decisão de infraestrutura com efeito no código.

Fechar este ticket atualiza a linha "Falhas" nas notas do mapa, que hoje está desatualizada.

## Resolução

Decisão registrada em [ADR 0001](../../adr/0001-politica-de-falhas.md). Grilling de duas
rodadas sobre os fatos da pesquisa do [003](003-rabbitmq-retry-dlq.md).

**Retry — híbrido barato, Caminho C fora.** `@Retry` + `@ExponentialBackoff` nos *adapters*
de I/O (MinIO em `extracao`, Postgres em `videos`, SMTP em `notificacao`), 2 tentativas com
backoff de segundos; `queue.x-queue-type=quorum` + `queue.x-delivery-limit=3` como rede
durável contra poison message. TTL+DLX manual foi rejeitado: o que falha de forma
transitória aqui são blips de I/O de segundos, e o que estoura memória numa extração de
4,4 GB estoura de novo depois do backoff. **Restrição do template:** o `AGENTS.md` proíbe
annotations de framework no `core`, então `@Retry` mora em `framework.service`, nunca no use
case — o que é o lugar certo de qualquer forma.

**Quorum + `at-least-once`, e não classic.** Entre perder a falha definitiva (Vídeo preso em
`PROCESSANDO`, e-mail nunca sai) e duplicar o e-mail, escolhemos não perder. Ressalva
levantada nesta sessão e ausente da pesquisa: com broker de **nó único** no Compose, o ganho
do `at-least-once` é quase teórico — quem carrega peso na escolha de quorum é o
`x-delivery-limit`.

**Guarda de unicidade em `videos`, na transição de estado.** `UPDATE ... WHERE id = ? AND
estado = ?`: a contagem de linhas afetadas *é* o token de unicidade, e só o update que mudou
a linha publica o evento. Não é regra de entrega no dono do estado — é o invariante "um
Vídeo cai para `FALHOU` uma única vez", do qual o e-mail é consequência. Mantém "só `videos`
tem banco" e deixa `notificacao` genuinamente sem estado.

**`extracao` consome a própria DLQ.** Buraco que o ticket não listava: nada reage à DLQ
sozinho, então mensagem envenenada deixaria o Vídeo em `PROCESSANDO` para sempre. O
`extracao` consome `extracao.comandos.dlq` e seu único trabalho ali é publicar a falha
definitiva — preserva "`extracao` executa e publica o que aconteceu" e mantém `videos`
ignorante do formato do comando. As DLQs de `videos` e `notificacao` são **terminais**, sem
consumidor: intervenção humana pelo management UI.

**Prefetch `max-outstanding-messages=1`, consumo `@Blocking`.** Um vídeo por réplica; o
worker é limitado por CPU e disco, e concorrência intra-processo multiplicaria o pico de
disco sem ganhar throughput. Paralelismo é `--scale extracao=3`, que ainda demonstra melhor.

**`failure-strategy=requeue` em todos os consumidores**, com `RabbitMQRejectMetadata(false)`
no nack para a falha permanente pular as tentativas e ir direto à DLQ. `fail` está fora:
derruba o health check e quebra o `depends_on: service_healthy`.

**`rabbitmq:4.3.5-management-alpine`** fixado nos Dev Services e no Compose (tag verificada
no Docker Hub nesta sessão). No 3.12 o `x-delivery-limit` não tem default e o requeue vira
laço infinito.

**Divergência aceita entre teste e produção:** `dead-letter-strategy=at-least-once` é
**policy de broker, não queue argument** (verificado na doc de quorum queues), e os Dev
Services só declaram topologia via `arguments`. A policy vai no `definitions.json` do
Compose; o teste roda `at-most-once`. A diferença só se manifesta em failover, que o
`@QuarkusTest` não exercita.

**Duas mudanças de vocabulário, gravadas no `CONTEXT.md`:** "tentativa" passa a significar
*entrega* (um crash consome uma, sem que nada tenha dado errado), e as transições de estado
do Vídeo são **idempotentes** — a mesma guarda condicional serve a `PROCESSANDO`,
`CONCLUIDO` e `FALHOU`, o que torna todo consumo de evento em `videos` idempotente pelo
mesmo mecanismo.

**Limitação aceita conscientemente:** o e-mail é *pelo menos uma vez*, não exatamente uma
vez — crash do `notificacao` entre o retorno do SMTP e o ack reenvia. Janela de
milissegundos; é o preço de `notificacao` sem banco.

**Névoa dissipada sem virar ticket:** "Idempotência do consumo" (chave do Pacote derivada do
id do Vídeo → reprocessar sobrescreve com conteúdo equivalente) e "Health check dos
consumidores" (`requeue` não reporta falha ao health).
