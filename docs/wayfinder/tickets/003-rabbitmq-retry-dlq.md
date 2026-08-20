# Retry, backoff e DLQ com RabbitMQ no Quarkus

- id: 003
- label: wayfinder:research
- status: fechado
- assignee: agente de pesquisa (sessao de 2026-08-20)
- bloqueado-por:

## Question

O requisito "em picos, o sistema não deve perder uma requisição" e a política de falha
(3 tentativas com backoff, falha permanente sem retry, DLQ ao esgotar) precisam de base
factual sobre o que a stack oferece de fato.

Investigar, contra a documentação oficial do Quarkus/SmallRye Reactive Messaging na
versão da plataforma que o template usa (3.31.3):

- Qual extensão usar (`quarkus-messaging-rabbitmq`) e como se configura consumo e
  publicação.
- Como se declara retry com backoff: é nativo do conector, é `x-message-ttl` +
  dead-letter-exchange no broker, ou precisa de `SmallRye Fault Tolerance`?
- Como se configura dead-letter-queue e o que acontece com a mensagem que a atinge.
- Como o consumidor distingue **nack com requeue** de **nack sem requeue** — o mecanismo
  que permite "falha permanente não gasta retry".
- Garantias de entrega: ack manual vs automático, e o que acontece se o worker morre no
  meio do processamento.
- Se os Dev Services do Quarkus sobem RabbitMQ automaticamente em teste.

Registre os achados em `docs/pesquisa/rabbitmq-retry-dlq.md`, com links para as fontes
primárias e trechos de configuração reais — não paráfrase.

## Resolução

Achados completos em [`docs/pesquisa/rabbitmq-retry-dlq.md`](../../pesquisa/rabbitmq-retry-dlq.md).
Fonte de verdade: código-fonte do conector `smallrye-rabbitmq` na tag 4.32.1 — **o guia de
referência que o Quarkus 3.31.3 publica está desatualizado** e omite `requeue`,
`queue.x-delivery-limit`, `dead-letter-ttl`, `publish-confirms` e `retry-on-fail-*`.

**Nativo da stack:**

- `quarkus-messaging-rabbitmq`, conector `smallrye-rabbitmq`, SmallRye RM 4.32.1 no BOM.
- DLQ é configuração pura: `auto-bind-dlq`, `dlx.declare`, `dead-letter-*`.
- Ack manual é o default (`auto-acknowledgement=false`); mensagem unacked volta à fila
  quando o canal fecha.
- `RabbitMQRejectMetadata(bool)` em `Message.nack(Throwable, Metadata)` controla o flag de
  requeue por mensagem — **é exatamente o mecanismo de "falha permanente não gasta retry"**.
- Publisher tem retry com backoff nativo (`retry-on-fail-attempts`=6,
  `retry-on-fail-interval`=5s) e `publish-confirms`, que vem **desligado** por default.
- Dev Services sobem RabbitMQ em teste e declaram exchanges/filas/bindings via properties.

**Precisa ser construído à mão:** backoff no consumo (só via `@Retry` + `@ExponentialBackoff`
da SmallRye Fault Tolerance, que é in-process); contagem durável de tentativas (só via fila
**quorum** com `x-delivery-limit`, que não tem backoff); o consumidor da DLQ que transita o
Vídeo para `FALHOU` (nada reage à DLQ sozinho); idempotência do e-mail.

**Contradiz a política fixada na cartografia** — sete pontos, os quatro primeiros graves:

1. **Não existe mecanismo único que faça "3 tentativas com backoff e sobreviva a crash".**
   São três caminhos mutuamente excludentes. O único completo (TTL + DLX manual) custa
   cerca de um dia de topologia à mão.
2. **Dead-lettering com fila *classic* é at-most-once** — pode perder exatamente a falha
   definitiva. Exige quorum + policy `dead-letter-strategy=at-least-once`, que por sua vez
   **admite duplicata**, atacando o requisito de "e-mail uma vez".
3. **"E-mail uma vez" não é garantido por nada da stack** (reentrega por crash,
   at-least-once, retry do publisher). Sugestão da pesquisa: usar a transição
   `PROCESSANDO → FALHOU` em `videos` como guarda de unicidade, o que preserva "só `videos`
   tem banco".
4. **Sem `max-outstanding-messages` não há prefetch limit**: o conector bufferiza até
   500.000 mensagens em memória. Para o `extracao` (que roda ffmpeg) tem de ser 1–2.
5. `failure-strategy=fail` derruba o health check — incompatível com o
   `depends_on: service_healthy` do Compose.
6. A imagem default dos Dev Services é `rabbitmq:3.12-management`, onde `x-delivery-limit`
   **não tem default** (o 20 só existe a partir do RabbitMQ 4.0).

Os pontos 1–3 reabrem a política de falhas: virou o ticket 010.
