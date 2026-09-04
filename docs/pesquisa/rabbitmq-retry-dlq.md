<!-- label: wayfinder:research -->
# Retry, backoff e DLQ com RabbitMQ no Quarkus 3.31.3

Pesquisa do ticket [`003`](../wayfinder/tickets/003-rabbitmq-retry-dlq.md).
Data: 2026-08-20.

## 0. Versões exatas em jogo (verificadas, não presumidas)

Do `quarkus-bom` 3.31.3 (`https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.31.3/quarkus-bom-3.31.3.pom`):

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-messaging-rabbitmq</artifactId>
  <version>3.31.3</version>
</dependency>
<dependency>
  <groupId>io.smallrye.reactive</groupId>
  <artifactId>smallrye-reactive-messaging-rabbitmq</artifactId>
  <version>4.32.1</version>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
  <version>3.31.3</version>
</dependency>
<dependency>
  <groupId>io.smallrye</groupId>
  <artifactId>smallrye-fault-tolerance</artifactId>
  <version>6.10.0</version>
</dependency>
```

Imagem padrão dos Dev Services, de `build-parent/pom.xml` da tag `3.31.3`
([fonte](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/build-parent/pom.xml)):

```xml
<rabbitmq.image>docker.io/library/rabbitmq:3.12-management</rabbitmq.image>
```

> **Aviso de confiabilidade das fontes.** O guia de referência que o Quarkus 3.31.3 publica
> (`docs/src/main/asciidoc/rabbitmq-reference.adoc` na tag 3.31.3) está **desatualizado em
> relação ao conector 4.32.1 que ele empacota**. O guia diz:
>
> ```
> If a message produced from a RabbitMQ message is nacked, a failure strategy is applied. The RabbitMQ connector supports
> three strategies, controlled by the failure-strategy channel setting:
>
> * `fail` - ...
> * `accept` - ...
> * `reject` - ...
> ```
> ([rabbitmq-reference.adoc, linhas 218-224, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-reference.adoc))
>
> Mas o código do conector 4.32.1 declara **quatro** estratégias e mais uma dúzia de
> atributos que a tabela do guia não lista (`requeue`, `queue.x-delivery-limit`,
> `dead-letter-ttl`, `dead-letter-dlx`, `publish-confirms`, `retry-on-fail-attempts`...).
> Nesta pesquisa a **fonte de verdade é o código-fonte do conector na tag 4.32.1**, com o
> guia usado só como confirmação secundária.

---

## 1. Qual extensão e como se configura consumo e publicação

**Extensão:** `io.quarkus:quarkus-messaging-rabbitmq`. É o artefato listado no guia
`rabbitmq.adoc` da tag 3.31.3:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-messaging-rabbitmq</artifactId>
</dependency>
```
([rabbitmq.adoc L79-83, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq.adoc))

O nome do conector é `smallrye-rabbitmq`:

> The connector name is: `smallrye-rabbitmq`.
>
> So, to indicate that a channel is managed by this connector you need:
> ```properties
> # Inbound
> mp.messaging.incoming.[channel-name].connector=smallrye-rabbitmq
>
> # Outbound
> mp.messaging.outgoing.[channel-name].connector=smallrye-rabbitmq
> ```
([smallrye-reactive-messaging 4.32.1, `rabbitmq/rabbitmq.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/rabbitmq.md))

Configuração mínima de consumo, verbatim da doc do conector 4.32.1:

```properties
rabbitmq-host=rabbitmq  # <1>
rabbitmq-port=5672      # <2>
rabbitmq-username=my-username   # <3>
rabbitmq-password=my-password   # <4>

mp.messaging.incoming.prices.connector=smallrye-rabbitmq # <5>
mp.messaging.incoming.prices.queue.name=my-queue         # <6>
mp.messaging.incoming.prices.routing-keys=urgent         # <7>
```
([`receiving-messages-from-rabbitmq.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/receiving-messages-from-rabbitmq.md))

Declarando exchange + fila + binding pelo próprio conector:

```properties
mp.messaging.incoming.people.connector=smallrye-rabbitmq
mp.messaging.incoming.people.exchange.declare=true
mp.messaging.incoming.people.queue.name=peopleQueue
mp.messaging.incoming.people.queue.declare=true
mp.messaging.incoming.people.queue.routing-keys=tall,short
```
(mesma fonte)

Ponto importante para os nossos três serviços: **canal de entrada aponta para uma _fila_,
canal de saída aponta para um _exchange_**.

```properties
# Configure the incoming RabbitMQ exchange `quote-requests`
mp.messaging.incoming.requests.connector=smallrye-rabbitmq
mp.messaging.incoming.requests.queue.name=quote-requests
mp.messaging.incoming.requests.exchange.name=quote-requests

# Configure the outgoing RabbitMQ exchange `quotes`
mp.messaging.outgoing.quotes.connector=smallrye-rabbitmq
mp.messaging.outgoing.quotes.exchange.name=quotes
```
([rabbitmq.adoc L286-296, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq.adoc))

E, do mesmo guia:

> `mp.messaging.[outgoing|incoming].{channel-name}.property=value`
>
> The `channel-name` segment must match the value set in the `@Incoming` and `@Outgoing` annotation

Consumo em Java, verbatim (a forma `Message<T>` é a que interessa, porque é a única que
permite `nack` com metadata):

```java
package rabbitmq.inbound;

import java.util.concurrent.CompletionStage;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;

@ApplicationScoped
public class RabbitMQPriceMessageConsumer {

    @Incoming("prices")
    public CompletionStage<Void> consume(Message<String> price) {
        // process your price.
        // Acknowledge the incoming message, marking the RabbitMQ message as `accepted`.
        return price.ack();
    }
}
```
([`receiving-messages-from-rabbitmq.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/receiving-messages-from-rabbitmq.md))

### Serialização (relevante para o contrato de mensagens, ticket 007)

> | T | RabbitMQ Message Body |
> | primitive types or `UUID`/`String` | String value with `content_type` set to `text/plain` |
> | `JsonObject` or `JsonArray` | Serialized String payload with `content_type` set to `application/json` |
> | `byte[]` | Binary content, with `content_type` set to `application/octet-stream` |
> | Any other class | The payload is converted to JSON (using a Json Mapper) then serialized with `content_type` set to `application/json` |
>
> If the message payload cannot be serialized to JSON, the message is _nacked_.
([rabbitmq-reference.adoc L228-242, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-reference.adoc))

Ou seja: um record Java sai como JSON com `content_type=application/json` e chega do outro
lado como `JsonObject` — sem módulo `shared`, sem jar comum. Isso é compatível com a
restrição do mapa de não ter módulo `shared`.

### Publicação: o lado outgoing tem retry e confirms nativos (não documentados no guia)

Do código do conector 4.32.1
([`RabbitMQConnector.java` L133-138](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java)):

```java
@ConnectorAttribute(name = "max-inflight-messages", direction = OUTGOING, description = "The maximum number of messages to be written to RabbitMQ concurrently; must be a positive number", type = "long", defaultValue = "1024")
@ConnectorAttribute(name = "default-routing-key", direction = OUTGOING, description = "The default routing key to use when sending messages to the exchange", type = "string", defaultValue = "")
@ConnectorAttribute(name = "default-ttl", direction = OUTGOING, description = "If specified, the time (ms) sent messages can remain in queues undelivered before they are dead", type = "long")
@ConnectorAttribute(name = "publish-confirms", direction = OUTGOING, description = "If set to true, published messages are acknowledged when the publish confirm is received from the broker", type = "boolean", defaultValue = "false")
@ConnectorAttribute(name = "retry-on-fail-attempts", direction = OUTGOING, description = "The number of tentative to retry on failure", type = "int", defaultValue = "6")
@ConnectorAttribute(name = "retry-on-fail-interval", direction = OUTGOING, description = "The interval (in seconds) between two sending attempts", type = "int", defaultValue = "5")
```

E o uso desses três em `RabbitMQMessageSender.send(...)`
([fonte L262-293](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/RabbitMQMessageSender.java)):

```java
final int retryAttempts = configuration.getRetryOnFailAttempts();
final int retryInterval = configuration.getRetryOnFailInterval();
...
Uni<Void> published;
if (publishConfirms) {
    published = publisher.publishConfirm(exchange, outgoingRabbitMQMessage.getRoutingKey(), ...)
            .onItem().invoke(deliveryTag -> OutgoingMessageMetadata.setResultOnMessage(msg, deliveryTag))
            .replaceWithVoid();
} else {
    published = publisher.publish(exchange, outgoingRabbitMQMessage.getRoutingKey(), ...);
}
return published
        .onFailure().retry().withBackOff(ofSeconds(1), ofSeconds(retryInterval)).atMost(retryAttempts)
        ...
```

**Achado operacional:** `publish-confirms` é `false` por padrão. Sem ele, o `Message.ack()`
do produtor dispara assim que o `basic.publish` sai do cliente, não quando o broker
confirma. Para o requisito "em picos, o sistema não deve perder uma requisição", o serviço
`videos` deve publicar com:

```properties
mp.messaging.outgoing.extracao-comandos.publish-confirms=true
```

---

## 2. Retry com backoff: nativo do conector? TTL+DLX? Fault Tolerance?

**Resposta curta: no lado do consumo, o conector RabbitMQ do SmallRye não tem retry com
backoff.** Ele tem quatro estratégias de falha e nenhuma delas conta tentativas ou espera.
Existem exatamente três caminhos, e eles não são equivalentes.

### Caminho A — `@Retry` da SmallRye Fault Tolerance (retry *in-process*)

O Quarkus documenta explicitamente a combinação Reactive Messaging + Fault Tolerance
(o exemplo está no guia do Kafka, mas o mecanismo é do Reactive Messaging, não do conector):

```java
@Incoming("kafka")
@Retry(delay = 10, maxRetries = 5)
public void consume(String v) {
   // ... retry if this method throws an exception
}
```
> You can configure the delay, the number of retries, the jitter, etc.
>
> If your method returns a `Uni` or `CompletionStage`, you need to add the `@NonBlocking` annotation:
> ```java
> @Incoming("kafka")
> @Retry(delay = 10, maxRetries = 5)
> @NonBlocking
> public Uni<String> consume(String v) { ... }
> ```
> NOTE: The `@NonBlocking` annotation is only required with SmallRye Fault Tolerance 5.1.0 and earlier.
> Starting with SmallRye Fault Tolerance 5.2.0 (available since Quarkus 2.1.0.Final), it is not necessary.
>
> The incoming messages are acknowledged only once the processing completes successfully.
> [...] If the processing still fails, even after all retries, the message is _nacked_ and the failure strategy is applied.

([kafka.adoc L415-448, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/kafka.adoc))

Essa última frase é a peça-chave da nossa política: **`@Retry` esgotado ⇒ `nack` ⇒
`failure-strategy` ⇒ DLQ**. É exatamente a cadeia "3 tentativas, esgotou, vai para DLQ".

Atributos de `@Retry` na SmallRye Fault Tolerance 6.10.0, com os defaults
([reference/retry](https://smallrye.io/docs/smallrye-fault-tolerance/6.10.0/reference/retry.html)):

| Atributo | Tipo | Default |
|---|---|---|
| `maxRetries` | int | 3 |
| `delay` | long | 0 millis |
| `delayUnit` | ChronoUnit | millis |
| `maxDuration` | long | 180000 millis (3 min) |
| `durationUnit` | ChronoUnit | millis |
| `jitter` | long | 200 millis |
| `jitterDelayUnit` | ChronoUnit | millis |
| `retryOn` | `Class<? extends Throwable>[]` | `{Exception.class}` |
| `abortOn` | `Class<? extends Throwable>[]` | `{}` |

Backoff exponencial é uma **anotação separada**, `@ExponentialBackoff`:

```java
@ApplicationScoped
public class MyService {
    @Retry
    @ExponentialBackoff
    public void hello() { ... }
}
```
> the delays between retry attempts grow exponentially, using a defined `factor`.
> [...] Default factor is 2; optional `maxDelay` defaults to 1 minute.
>
> It is an error to add `@ExponentialBackoff` to a program element that doesn't have `@Retry`.
> It is also an error to add more than one backoff annotation to the same program element.

([reference/retry, SmallRye FT 6.10.0](https://smallrye.io/docs/smallrye-fault-tolerance/6.10.0/reference/retry.html);
irmãs: `@FibonacciBackoff`, `@CustomBackoff`)

Override por configuração, sintaxe nativa do Quarkus:

```properties
quarkus.fault-tolerance."org.acme.CoffeeResource/coffees".retry.max-retries=6
```
([guia smallrye-fault-tolerance do Quarkus](https://quarkus.io/guides/smallrye-fault-tolerance))

**Limite honesto do caminho A:** o retry acontece dentro do processo, com a mensagem ainda
*unacked* no broker. Um `@ExponentialBackoff` com 3 tentativas segurando um worker de
extração de vídeo por dezenas de segundos ocupa o slot de prefetch e, se o worker morrer no
meio, **todas as tentativas gastas são perdidas** — a mensagem é reenfileirada com contador
zerado (ver §5). Também não sobrevive a restart.

### Caminho B — `failure-strategy=requeue` + `queue.x-delivery-limit` em fila quorum (retry *no broker*)

Este é o caminho que dá "N tentativas e depois DLQ" **sem escrever uma linha de contagem**.
Duas peças:

1. O conector 4.32.1 tem a estratégia `requeue`
   ([`RabbitMQFailureHandler.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQFailureHandler.java)):

   ```java
   interface Strategy {
       String FAIL = "fail";
       String ACCEPT = "accept";
       String REJECT = "reject";
       String REQUEUE = "requeue";
   }
   ```
   > -   `requeue` - this strategy marks the RabbitMQ message as rejected
   >     with requeue flag to true. The processing continues with the next message,
   >     but the requeued message will be redelivered to the consumer.

   ([`receiving-messages-from-rabbitmq.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/receiving-messages-from-rabbitmq.md))

2. O conector expõe `queue.x-delivery-limit`
   ([`RabbitMQConnector.java` L101](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java)):

   ```java
   @ConnectorAttribute(name = "queue.x-delivery-limit", direction = INCOMING, description = "If queue.x-queue-type is quorum, when a message has been returned more times than the limit the message will be dropped or dead-lettered", type = "long")
   ```

   E ele é de fato aplicado na declaração da fila
   ([`IncomingRabbitMQChannel.java` L193-194](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)):

   ```java
   //x-delivery-limit
   ic.getQueueXDeliveryLimit().ifPresent(deliveryLimit -> queueArgs.put("x-delivery-limit", deliveryLimit));
   ```

Do lado do broker ([RabbitMQ — Quorum Queues, Poison Message Handling](https://www.rabbitmq.com/docs/quorum-queues)):

> Quorum queues keep track of the number of unsuccessful (re)delivery attempts and expose it
> in the "x-delivery-count" header.
>
> When a message has been redelivered more times than the limit the message will be dropped
> (removed) or dead-lettered (if a DLX is configured).
>
> Starting with RabbitMQ 4.0, the delivery limit for quorum queues defaults to 20.

**Cuidado com a versão:** os Dev Services do Quarkus 3.31.3 sobem `rabbitmq:3.12-management`
(§6). No 3.12 **não existe default de 20** — `x-delivery-limit` tem de ser declarado
explicitamente, senão o requeue é infinito. O javadoc do próprio conector avisa
([`IncomingRabbitMQMessage.java` L141-152](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/IncomingRabbitMQMessage.java)):

> Rejects the message by nack'ing it.
> This will either discard the message for good, requeue (if requeue=true is set)
> or (if a DLQ has been set up) send it to the DLQ.
> Please note that requeue is potentially dangerous as it can lead to
> very high load if all consumers reject and requeue a message repeatedly.

**Limite honesto do caminho B:** `x-delivery-limit` conta tentativas, mas **não introduz
backoff**. O requeue é imediato — a mensagem volta para a fila e é reentregue assim que o
consumidor tiver crédito de prefetch. Três tentativas queimam em milissegundos.

### Caminho C — TTL + DLX (fila de espera): o único backoff que sobrevive a crash

Este é o padrão clássico e é 100% broker-side, sem extensão. Os blocos primários:

Do [RabbitMQ — Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx), as causas de
dead-lettering (verbatim, condensado):

> Messages are dead-lettered when:
> 1. The message is negatively acknowledged by an AMQP 1.0 receiver using the `rejected`
>    outcome or by an AMQP 0.9.1 consumer using `basic.reject` or `basic.nack`
>    (with `requeue=false`)
> 2. The message expires due to per-message TTL
> 3. The message is dropped because its queue exceeded a length limit
> 4. The message is returned more times to a quorum queue than the delivery-limit

Do [RabbitMQ — TTL](https://www.rabbitmq.com/docs/ttl):

> Message TTL can be set for a given queue by setting the `message-ttl` argument with a
> policy or by specifying the same argument at the time of queue declaration.
>
> A TTL can be specified on a per-message basis, by setting the `expiration` property when
> publishing a message.
>
> Only when expired messages reach the head of a queue will they actually be discarded
> (marked for deletion). [...] expired messages can queue up behind non-expired ones until
> the latter are consumed or expired.

O arranjo: fila principal com DLX apontando para um exchange de retry → fila de espera com
`x-message-ttl=<backoff>` e `x-dead-letter-exchange` de volta para o exchange principal.
O `nack(requeue=false)` do consumidor manda a mensagem para a espera; a expiração do TTL a
devolve para a fila principal. O contador de tentativas fica no header `x-death`:

> The x-death header (AMQP 0.9.1) is an array containing objects with these fields:
> `queue`, `reason`, `count` — "How many times this message was dead lettered from this
> queue for this reason" — `exchange`, `routing-keys`, `time`, `original-expiration`.
>
> Dead-lettering reasons are: `rejected`, `expired`, `maxlen`, or `delivery_limit`.

([RabbitMQ — Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx))

Duas armadilhas documentadas nesse padrão:

1. **Detecção de ciclo.**
   > To prevent automatic infinite message looping within RabbitMQ, RabbitMQ will detect a
   > cycle and drop the message if there was no rejection in the entire cycle.

   No nosso ciclo há rejeição (o `basic.nack` do consumidor), então o ciclo **não** é
   descartado. Mas um ciclo puramente de TTL seria.
2. **Head-of-queue.** Por causa da regra citada acima, uma fila de espera com TTL uniforme
   funciona (FIFO, todos expiram na mesma ordem). Backoff exponencial exige **uma fila de
   espera por nível** (ex.: `.retry.10s`, `.retry.30s`, `.retry.90s`), ou TTL por mensagem
   e a certeza de que a ordem de expiração é a ordem de enfileiramento.

O conector oferece as peças para montar o segundo hop sem código, porque a DLQ que ele
declara também aceita TTL e DLX próprios
([`RabbitMQConnector.java` L115-117](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java)):

```java
@ConnectorAttribute(name = "dead-letter-ttl", direction = INCOMING, description = "If specified, the time (ms) for which a message can remain in DLQ undelivered before it is dead. Relevant only if auto-bind-dlq is true", type = "long")
@ConnectorAttribute(name = "dead-letter-dlx", direction = INCOMING, description = "If specified, a DLX to assign to the DLQ. Relevant only if auto-bind-dlq is true", type = "string")
@ConnectorAttribute(name = "dead-letter-dlx-routing-key", direction = INCOMING, description = "If specified, a dead letter routing key to assign to the DLQ. Relevant only if auto-bind-dlq is true", type = "string")
```

e o `RabbitMQClientHelper.configureDLQorDLX` traduz isso literalmente para argumentos AMQP
([fonte L266-278](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/RabbitMQClientHelper.java)):

```java
// x-dead-letter-exchange
ic.getDeadLetterDlx().ifPresent(deadLetterDlx -> queueArgs.put("x-dead-letter-exchange", deadLetterDlx));
// x-dead-letter-routing-key
ic.getDeadLetterDlxRoutingKey().ifPresent(deadLetterDlx -> queueArgs.put("x-dead-letter-routing-key", deadLetterDlx));
// x-queue-type
ic.getDeadLetterQueueType().ifPresent(queueType -> queueArgs.put("x-queue-type", queueType));
// x-queue-mode
ic.getDeadLetterQueueMode().ifPresent(queueMode -> queueArgs.put("x-queue-mode", queueMode));
// x-message-ttl
ic.getDeadLetterTtl().ifPresent(queueTtl -> {
    if (queueTtl >= 0) {
        queueArgs.put("x-message-ttl", queueTtl);
    } else {
        throw ex.illegalArgumentInvalidQueueTtl();
    }
});
```

Ou seja: **a "DLQ" do conector pode ser configurada como fila de espera de retry**
(`dead-letter-ttl` + `dead-letter-dlx` de volta ao exchange principal). O que o conector
**não** faz é contar tentativas para você — isso é `x-death.count` lido à mão, ou
`x-delivery-limit` em fila quorum.

### Veredito sobre backoff

| Requisito | Nativo? |
|---|---|
| Retry no publish com backoff | **Sim** — `retry-on-fail-attempts` / `retry-on-fail-interval` (defaults 6 / 5s) |
| Retry no consumo, N tentativas, sem espera | **Sim** — `failure-strategy=requeue` + `queue.x-queue-type=quorum` + `queue.x-delivery-limit` |
| Retry no consumo, N tentativas **com backoff**, in-process | **Sim, via outra extensão** — `quarkus-smallrye-fault-tolerance`, `@Retry` + `@ExponentialBackoff` |
| Retry no consumo, N tentativas **com backoff**, durável (sobrevive a crash) | **Não** — exige topologia TTL+DLX montada à mão e leitura de `x-death` |

---

## 3. Como se configura DLQ e o que acontece com a mensagem que a atinge

O conector declara e liga a DLQ sozinho. Tabela completa dos atributos, verbatim das
`@ConnectorAttribute` de `RabbitMQConnector.java` 4.32.1 (L105-117):

| Atributo | Descrição (verbatim) | Default |
|---|---|---|
| `auto-bind-dlq` | Whether to automatically declare the DLQ and bind it to the binder DLX | `false` |
| `dead-letter-queue-name` | The name of the DLQ; if not supplied will default to the queue name with '.dlq' appended | — |
| `dead-letter-exchange` | A DLX to assign to the queue. Relevant only if auto-bind-dlq is true | `DLX` |
| `dead-letter-exchange-type` | The type of the DLX to assign to the queue. Relevant only if auto-bind-dlq is true | `direct` |
| `dead-letter-exchange.arguments` | The identifier of the key-value Map exposed as bean used to provide arguments for dead-letter-exchange creation | — |
| `dead-letter-routing-key` | A dead letter routing key to assign to the queue; if not supplied will default to the queue name | — |
| `dlx.declare` | Whether to declare the dead letter exchange binding. Relevant only if auto-bind-dlq is true; set to false if these are expected to be set up independently | `false` |
| `dead-letter-queue-type` | If automatically declare DLQ, we can choose different types of DLQ [quorum, classic, stream] | — |
| `dead-letter-queue-mode` | If automatically declare DLQ, we can choose different modes of DLQ [lazy, default] | — |
| `dead-letter-queue.arguments` | The identifier of the key-value Map exposed as bean used to provide arguments for dead-letter-queue creation | — |
| `dead-letter-ttl` | If specified, the time (ms) for which a message can remain in DLQ undelivered before it is dead. Relevant only if auto-bind-dlq is true | — |
| `dead-letter-dlx` | If specified, a DLX to assign to the DLQ. Relevant only if auto-bind-dlq is true | — |
| `dead-letter-dlx-routing-key` | If specified, a dead letter routing key to assign to the DLQ. Relevant only if auto-bind-dlq is true | — |

O que `auto-bind-dlq=true` faz na fila **principal**
([`IncomingRabbitMQChannel.declareQueue`, L177-180](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)):

```java
if (ic.getAutoBindDlq()) {
    queueArgs.put("x-dead-letter-exchange", ic.getDeadLetterExchange());
    queueArgs.put("x-dead-letter-routing-key", ic.getDeadLetterRoutingKey().orElse(queueName));
}
```

Config mínima para a nossa fila de comandos de extração:

```properties
mp.messaging.incoming.extracao-comandos.connector=smallrye-rabbitmq
mp.messaging.incoming.extracao-comandos.queue.name=extracao.comandos
mp.messaging.incoming.extracao-comandos.exchange.name=extracao
mp.messaging.incoming.extracao-comandos.queue.declare=true
mp.messaging.incoming.extracao-comandos.exchange.declare=true

# DLQ declarada e ligada pelo conector
mp.messaging.incoming.extracao-comandos.auto-bind-dlq=true
mp.messaging.incoming.extracao-comandos.dlx.declare=true
mp.messaging.incoming.extracao-comandos.dead-letter-exchange=extracao.dlx
mp.messaging.incoming.extracao-comandos.dead-letter-exchange-type=direct
mp.messaging.incoming.extracao-comandos.dead-letter-queue-name=extracao.comandos.dlq
mp.messaging.incoming.extracao-comandos.dead-letter-routing-key=extracao.comandos
```

**O que acontece com a mensagem que chega na DLQ:** nada. A DLQ é uma fila comum. Ela fica
lá até alguém consumir. Não há reprocessamento automático, não há alerta, não há evento.
Para que "esgotado vai para DLQ e o vídeo vira FALHOU" aconteça, **alguém tem de consumir a
DLQ** — um segundo canal `@Incoming` apontando para `extracao.comandos.dlq` que publica o
evento de falha definitiva. Isso é código nosso, não é do conector.

Um risco de perda documentado, importante para "não perder uma requisição"
([RabbitMQ — Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx)):

> By default, dead-lettering occurs "without publisher confirms turned on internally."
> Only quorum queues support at-least-once guarantees.

e ([RabbitMQ — Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues)):

> Quorum queues support a safer form of dead-lettering that uses `at-least-once` guarantees
> for the message transfer between queues. [This requires setting the `dead-letter-strategy`
> policy to `at-least-once` and configuring `overflow` to `reject-publish`.]

Ou seja: com fila **classic**, a transferência para a DLQ é *at-most-once* — pode perder a
mensagem exatamente no momento em que ela mais importa (a falha definitiva que gera o
e-mail). Fila **quorum** + política `dead-letter-strategy=at-least-once` é a única
configuração que garante que a falha definitiva chega na DLQ. O conector permite escolher o
tipo (`queue.x-queue-type=quorum`, `dead-letter-queue-type=quorum`), mas a **policy**
`dead-letter-strategy` não tem atributo no conector — tem de ser aplicada no broker
(`rabbitmqctl set_policy` / `definitions.json` no Compose).

---

## 4. Nack com requeue vs. nack sem requeue — o mecanismo de "falha permanente não gasta retry"

### No protocolo

([RabbitMQ — Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms))

> - **basic.reject**: Negatively acknowledges a single delivery
> - **basic.nack** (RabbitMQ extension): Supports both single and multiple deliveries via a `multiple` parameter
>
> Both methods include a `requeue` field: when `true`, messages return to the queue;
> when `false`, messages are routed to Dead Letter Exchange if configured, otherwise discarded.

### No conector

Cada estratégia decide o flag `requeue`, mas **a metadata do `nack` tem precedência**.
`RabbitMQReject` (default) — precedência sobre `false`
([fonte](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQReject.java)):

```java
boolean requeue = Optional.ofNullable(metadata)
        .flatMap(md -> md.get(RabbitMQRejectMetadata.class))
        .map(RabbitMQRejectMetadata::isRequeue).orElse(false);
return ClientHolder.runOnContext(context, msg, m -> m.rejectMessage(reason, requeue));
```

`RabbitMQRequeue` — mesmo código, `orElse(true)`
([fonte](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQRequeue.java)):

```java
boolean requeue = Optional.ofNullable(metadata)
        .flatMap(md -> md.get(RabbitMQRejectMetadata.class))
        .map(RabbitMQRejectMetadata::isRequeue).orElse(true);
```

E a doc:

> The RabbitMQ reject `requeue` flag can be controlled on different failure strategies
> using the `RabbitMQRejectMetadata`. To do that, use the `Message.nack(Throwable, Metadata)`
> method by including the `RabbitMQRejectMetadata` metadata with `requeue` to `true`.

([`receiving-messages-from-rabbitmq.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/receiving-messages-from-rabbitmq.md))

Exemplo oficial, verbatim
([`RabbitMQRejectMetadataExample.java`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/java/rabbitmq/inbound/RabbitMQRejectMetadataExample.java)):

```java
package rabbitmq.inbound;

import java.util.concurrent.CompletionStage;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import io.smallrye.reactive.messaging.rabbitmq.RabbitMQRejectMetadata;

@ApplicationScoped
public class RabbitMQRejectMetadataExample {

    @Incoming("in")
    public CompletionStage<Void> consume(Message<String> message) {
        return message.nack(new Exception("Failed!"), Metadata.of(
                new RabbitMQRejectMetadata(true)));
    }
}
```

### É este o mecanismo de "falha permanente não gasta retry"

Com `failure-strategy=requeue` como default do canal, o consumidor decide caso a caso:

- **falha transitória** (MinIO fora do ar, timeout de rede) → `nack` sem metadata → herda o
  default da estratégia (`requeue=true`) → volta para a fila, incrementa `x-delivery-count`.
- **falha permanente** (vídeo corrompido, formato não suportado, `sub` ausente) →
  `nack(erro, Metadata.of(new RabbitMQRejectMetadata(false)))` → `requeue=false` → **vai
  direto para a DLQ sem passar pelas tentativas restantes**.

Alternativamente, com `failure-strategy=reject` (default) o polo se inverte: permanente é o
`nack` cru e transitório é o que carrega `RabbitMQRejectMetadata(true)`. Para nós, escolher
`reject` como base e marcar explicitamente o transitório é mais seguro: **o esquecimento
falha para o lado da DLQ, não para o lado do loop infinito.**

O terceiro caminho, se o comportamento precisar ser mais fino que dois flags, é uma
estratégia própria:

> In addition, you can also provide your own failure strategy. To provide a failure strategy
> implement a bean exposing the interface `RabbitMQFailureHandler`, qualified with a
> `@Identifier`. Set the name of the bean as the `failure-strategy` channel setting.
>
> !!!warning "Experimental"
>     `RabbitMQFailureHandler` is experimental and APIs are subject to change in the future

(mesma fonte). A interface, verbatim:

```java
public interface RabbitMQFailureHandler {
    <V> CompletionStage<Void> handle(IncomingRabbitMQMessage<V> message, Metadata metadata, Context context, Throwable reason);
}
```

E o `failure-strategy` aceita nome de bean — o guia 3.31.3 não diz isso, o código diz:

```java
@ConnectorAttribute(name = "failure-strategy", direction = INCOMING, description = "The failure strategy to apply when a RabbitMQ message is nacked. Accepted values are `fail`, `accept`, `reject` (default), `requeue` or name of a bean", type = "string", defaultValue = "reject")
```

### Como o `nack` chega até a estratégia

O default de acknowledgement depende da assinatura do método. Com
`@Acknowledgment(POST_PROCESSING)` (o recomendado), o nack é automático
([`concepts/acknowledgement.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/concepts/acknowledgement.md)):

> If your method uses the `POST_PROCESSING` acknowledgment strategy, and the method fails
> (either by throwing an exception or by producing a failure), the message is automatically
> nacked with the caught exception

> It is recommended to use `POST_PROCESSING` as it guarantees that the full processing has
> completed before acknowledging the incoming message.

As quatro estratégias, verbatim:

> -   `POST_PROCESSING` - the acknowledgement of the incoming message is executed once the produced message is acknowledged.
> -   `PRE_PROCESSING` - the acknowledgement of the incoming message is executed before the message is processed by the method.
> -   `MANUAL` - the acknowledgement is done by the user.
> -   `NONE` - No acknowledgment is performed, neither manually or automatically.

Para poder passar `RabbitMQRejectMetadata`, o método precisa receber `Message<T>` e usar
`MANUAL` — o nack automático do `POST_PROCESSING` não carrega metadata.

---

## 5. Garantias de entrega: ack manual vs. automático, e o worker que morre no meio

### O atributo

```java
@ConnectorAttribute(name = "auto-acknowledgement", direction = INCOMING, description = "Whether the received RabbitMQ messages must be acknowledged when received; if true then delivery constitutes acknowledgement", type = "boolean", defaultValue = "false")
```
([`RabbitMQConnector.java` L122](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java))

Ele vira literalmente o `autoAck` do cliente Vert.x
([`IncomingRabbitMQChannel.java` L226-231](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)):

```java
QueueOptions queueOptions = new QueueOptions();
...
        .setAutoAck(ic.getAutoAcknowledgement())
```

**O default é `false`** — ack manual. É o que queremos; não precisa mexer.

### O que o broker diz

([RabbitMQ — Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms))

Sobre o modo automático:

> [fire-and-forget] trades off higher throughput (as long as the consumers can keep up) for
> reduced safety of delivery and consumer processing.

A doc classifica ack automático como inseguro e sujeito a perda se a conexão do consumidor
cair antes do processamento terminar.

Sobre o worker que morre no meio — esta é a resposta direta à pergunta do ticket:

> When manual acknowledgements are used, any delivery (message) that was not acked is
> automatically requeued when the channel (or connection) on which the delivery happened is
> closed.

E a marca da reentrega:

> Redeliveries will have a special boolean property, `redeliver`, set to `true` by RabbitMQ.
> For first time deliveries it will be set to `false`.

**Consequência para a nossa política:** se o worker de `extracao` morrer no meio de um
ffmpeg, a mensagem volta para a fila e é reentregue — mas isso **não conta como uma das 3
tentativas** se o contador estiver em `@Retry` (in-process, perdido junto com o processo).
Se o contador estiver no broker (`x-delivery-count` de fila quorum), o crash **conta**,
porque o RabbitMQ incrementa a contagem de reentrega. São semânticas diferentes e a decisão
precisa escolher uma:

- contador in-process → crash é "de graça", risco de loop infinito de crash;
- contador no broker → crash gasta tentativa, poison message morre em 3 crashes.

Para extração de vídeo (processo caro, com risco real de OOM em vídeo grande), o contador
no broker é o que protege o sistema. Ver §7.

### Prefetch / concorrência

```java
@ConnectorAttribute(name = "max-outstanding-messages", direction = INCOMING, description = "The maximum number of outstanding/unacknowledged messages being processed by the connector at a time; must be a positive number", type = "int")
```

Aplicado como `basic.qos` ([`IncomingRabbitMQChannel.java` L119-120](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)):

```java
if (ic.getMaxOutstandingMessages().isPresent()) {
    uni = client.basicQos(ic.getMaxOutstandingMessages().get(), false);
}
```

Sem esse atributo, **não há prefetch limit** — o conector puxa até
`max-incoming-internal-queue-size` (default `500000`) mensagens para uma fila interna em
memória. Para um worker de ffmpeg isso é catastrófico em pico: meio milhão de comandos
unacked na heap, e um crash devolve tudo de uma vez. Para `extracao`:

```properties
mp.messaging.incoming.extracao-comandos.max-outstanding-messages=1
```

(A doc do broker recomenda 100-300 para throughput; aqui o trabalho é minutos de CPU por
mensagem, então 1 ou 2 é o valor certo.)

### Processamento bloqueante

ffmpeg bloqueia. O método `@Incoming` roda na event loop do Vert.x por padrão; sem
`@Blocking` isso trava o reator.

```java
@Outgoing("Y")
@Incoming("X")
@Blocking
public String process(String s) {
  return s.toUpperCase();
}
```
> By default, use of `@Blocking` results in the method being executed in the Vert.x worker pool.
> If it's desired to execute methods on a custom worker pool, with specific concurrency needs,
> it can be defined on `@Blocking`:
> ```java
> @Blocking("my-custom-pool")
> ```
> Specifying the concurrency for the above worker pool requires the following configuration
> property to be defined:
> ```
> smallrye.messaging.worker.my-custom-pool.max-concurrency=3
> ```
([`concepts/blocking.md`, 4.32.1](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/concepts/blocking.md))

### Health check

> If you use the RabbitMQ connector with the `quarkus-smallrye-health` extension, it
> contributes to the readiness and liveness probes. [...]
> Note that a message processing failure nacks the message, which is then handled by the
> `failure-strategy`. It's the responsibility of the `failure-strategy` to report the
> failure and influence the outcome of the checks. The `fail` failure strategy reports the
> failure, and so the check will report the fault.
([rabbitmq-reference.adoc L405-419, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-reference.adoc))

Corolário: **`failure-strategy=fail` derruba a saúde do serviço**. Como o mapa usa
`depends_on: service_healthy`, `fail` está fora de questão para nós — uma extração ruim
tornaria o serviço `extracao` unhealthy.

---

## 6. Dev Services sobem RabbitMQ em teste?

**Sim.** Do guia `rabbitmq-dev-services.adoc` na tag 3.31.3
([fonte](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-dev-services.adoc)):

> Dev Services for RabbitMQ automatically starts a RabbitMQ broker in dev mode and when
> running tests. So, you don't have to start a broker manually. The application is
> configured automatically.
>
> Dev Services for RabbitMQ is automatically enabled unless:
> - `quarkus.rabbitmq.devservices.enabled` is set to `false`
> - the `rabbitmq-host` or `rabbitmq-port` is configured
> - all the Reactive Messaging RabbitMQ channels have the `host` or `port` attributes set
>
> Dev Services for RabbitMQ relies on Docker to start the broker.

Sobre compartilhamento — relevante porque o mapa exige teste **por serviço e isolado**:

> Sharing is enabled by default in dev mode, but disabled in test mode.
> You can disable the sharing with `quarkus.rabbitmq.devservices.shared=false`.

Ou seja, em `@QuarkusTest` cada aplicação já ganha o próprio broker por padrão. Bom para nós.

### Topologia pré-definida — dá para declarar DLX/DLQ/filas de espera só com config

> Dev Services for RabbitMQ supports defining topology upon broker start. You can define
> Virtual Hosts, Exchanges, Queues, and Bindings through standard Quarkus configuration.

```properties
quarkus.rabbitmq.devservices.exchanges.my-exchange.type=topic            # defaults to 'direct'
quarkus.rabbitmq.devservices.exchanges.my-exchange.auto-delete=false     # defaults to 'false'
quarkus.rabbitmq.devservices.exchanges.my-exchange.durable=true          # defaults to 'false'
quarkus.rabbitmq.devservices.exchanges.my-exchange.vhost=my-vhost        # defaults to '/'
```

```properties
quarkus.rabbitmq.devservices.queues.my-queue.auto-delete=false          # defaults to 'false'
quarkus.rabbitmq.devservices.queues.my-queue.durable=true               # defaults to 'false'
quarkus.rabbitmq.devservices.queues.my-queue.vhost=my-vhost             # defaults to '/'
```

```properties
quarkus.rabbitmq.devservices.queues.my-queue.arguments.x-dead-letter-exchange=another-exchange
```

```properties
quarkus.rabbitmq.devservices.bindings.a-binding.source=my-exchange      # defaults to name of binding
quarkus.rabbitmq.devservices.bindings.a-binding.routing-key=some-key    # defaults to '#'
quarkus.rabbitmq.devservices.bindings.a-binding.destination=my-queue    # defaults to name of binding
quarkus.rabbitmq.devservices.bindings.a-binding.destination-type=queue  # defaults to 'queue'
quarkus.rabbitmq.devservices.bindings.a-binding.vhost=my-vhost          # defaults to '/'
```

(todas as quatro citações verbatim do `rabbitmq-dev-services.adoc` da tag 3.31.3)

Isso resolve o teste da topologia de retry: uma fila de espera com
`arguments.x-message-ttl` e `arguments.x-dead-letter-exchange` é declarável em
`application.properties` de teste, sem `@BeforeAll` com cliente AMQP.

### Ressalva de versão

A imagem default é `docker.io/library/rabbitmq:3.12-management`, configurável:

```properties
quarkus.rabbitmq.devservices.image-name=docker.io/library/rabbitmq:3.12-management
```

Se a decisão for usar filas quorum com `x-delivery-limit` e/ou
`dead-letter-strategy=at-least-once`, vale fixar uma imagem 4.x tanto no Dev Services
quanto no Compose, para que teste e produção concordem. Em 3.12 o `x-delivery-limit` existe
mas **não tem default**.

---

## 7. Confronto com a política decidida no mapa

Política do mapa: *"Retry 3x com backoff para falhas transitórias; falha permanente não
gasta retry; esgotado vai para DLQ, vídeo vira `FALHOU`, `notificacao` envia e-mail **uma
vez**."*

| Peça da política | Situação factual |
|---|---|
| falha permanente não gasta retry | **Sustentada.** `RabbitMQRejectMetadata(false)` + `failure-strategy=requeue`, ou `nack` cru + `failure-strategy=reject`. Mecanismo nativo, documentado, uma linha. |
| esgotado vai para DLQ | **Sustentada** no transporte (`auto-bind-dlq=true`), com ressalvas em (a) e (c) abaixo. |
| 3 tentativas | **Sustentada**, mas com duas semânticas incompatíveis; ver (b). |
| **com backoff** | **Não é nativo.** Ver (b). |
| vídeo vira `FALHOU` | **Código nosso.** Nada no conector reage à chegada de mensagem na DLQ. |
| e-mail **uma vez** | **Não é garantido por nada da stack.** Ver (c). |

### (a) Contradição: a transferência para a DLQ pode perder a mensagem

Com fila **classic** (default do conector: `queue.x-queue-type` não é setado, e a DLQ é
declarada com `queueDeclare(name, true, false, false, args)` sem tipo), o dead-lettering é
*at-most-once* pela doc do broker. Isso colide frontalmente com "em picos, o sistema não
deve perder uma requisição" **no ponto exato onde a perda é mais visível**: o usuário nunca
receberia o e-mail de falha e o vídeo ficaria eternamente em `PROCESSANDO`.

Mitigação: `queue.x-queue-type=quorum` + `dead-letter-queue-type=quorum` + policy
`dead-letter-strategy=at-least-once` com `overflow=reject-publish` no broker.
Consequência: **`at-least-once` significa duplicata possível na DLQ** — o que ataca
diretamente o "e-mail uma vez".

### (b) Contradição: "3 tentativas com backoff" não é uma coisa só

Não existe, na stack, um mecanismo que faça as três coisas ao mesmo tempo (contar 3,
esperar entre elas, e sobreviver a um crash do worker). É preciso escolher:

- **`@Retry(maxRetries=2) + @ExponentialBackoff`**: backoff real, mas as tentativas somem se
  o processo morrer, e a mensagem fica unacked segurando prefetch durante toda a espera.
  Fácil, honesto para falhas de I/O curtas (MinIO, Postgres). Ruim para o ffmpeg.
- **`failure-strategy=requeue` + quorum + `queue.x-delivery-limit=3`**: contagem durável e
  gratuita, o crash conta como tentativa (o que é *desejável* para um worker de vídeo), mas
  **backoff zero** — as 3 tentativas queimam instantaneamente.
- **TTL+DLX com filas de espera**: é o único que dá backoff durável, e é o mais caro:
  1 exchange + N filas de espera + leitura de `x-death.count` + republicação manual.
  Estimativa: é um dia de trabalho, não uma linha de properties.

Dadas as 5,5 semanas solo do mapa, a recomendação factual é o **híbrido barato**:
`@Retry` in-process (curto, ~2 tentativas com backoff de segundos) para o I/O transitório
*dentro* do use case, e `x-delivery-limit` na fila quorum como rede de segurança durável
contra poison message. Vale registrar no mapa que "backoff" nesse arranjo significa
"segundos dentro da tentativa", não "minutos entre reentregas".

### (c) Contradição: "e-mail uma vez" não é entregue por nada aqui

Três fontes independentes de duplicata, todas documentadas:

1. Reentrega por crash do worker: *"any delivery (message) that was not acked is
   automatically requeued when the channel (or connection) [...] is closed"*
   — se o worker morre **depois** de publicar o evento de falha e **antes** do ack, o evento
   sai duas vezes.
2. `dead-letter-strategy=at-least-once` (necessário por (a)) admite duplicata por definição.
3. `retry-on-fail-attempts` (default 6) no lado do publisher republica em caso de falha de
   envio, e sem `publish-confirms` não há como distinguir "não chegou" de "chegou e a
   confirmação se perdeu".

Não há atributo de deduplicação no conector RabbitMQ do SmallRye (o conector Kafka tem
`enable.idempotence`; o RabbitMQ não tem equivalente no consumo). **A idempotência do e-mail
tem de ser estado do serviço `notificacao`** — por exemplo, uma tabela/marca de
"notificação já enviada para o vídeo X". Isso cruza com o item "Idempotência do consumo" da
seção *Ainda não especificado* do mapa e provavelmente merece virar ticket próprio; note que
o mapa hoje diz que **só `videos` tem banco**, e essa marca precisa morar em algum lugar
durável.

Alternativa sem banco em `notificacao`: `videos` é o dono do estado e a transição
`PROCESSANDO → FALHOU` é a guarda de unicidade (só a transição que efetivamente mudou a
linha publica o evento de notificação). Isso mantém a restrição "só `videos` tem banco" e
move a idempotência para onde já existe estado transacional.

---

## 8. Adendo (ticket 030): o conector espera a mensagem em voo terminar no `SIGTERM`?

Pergunta central do [ticket 030](../wayfinder/tickets/030-deploy-nao-gasta-tentativa.md):
`stop_grace_period` acima do teto de relógio de uma Extração (`timeout-ffprobe-segundos=30`
+ `timeout-ffmpeg-segundos=300` + download/upload) resolve o deploy que mata uma Extração em
voo **se e somente se** o conector parar de puxar mensagem no `SIGTERM` e deixar a que está
em voo terminar e dar ack antes de fechar o canal.

**Resposta: não.** O conector cancela a assinatura e fecha a conexão de forma síncrona e
incondicional assim que o evento de destruição do CDI dispara — sem checar se há uma
mensagem em processamento, sem esperar `@Blocking` terminar, e sem que `stop_grace_period`
ou `quarkus.shutdown.timeout` participem dessa decisão. Um grace period de minutos não muda
quando isso acontece: acontece em milissegundos após o `SIGTERM`, sempre.

### O que o conector faz no shutdown

`RabbitMQConnector.terminate()` 4.32.1 observa `@BeforeDestroyed(ApplicationScoped.class)` —
o evento de início de destruição do contexto CDI `ApplicationScoped` — e não recebe nenhuma
informação sobre trabalho em andamento
([`RabbitMQConnector.java` L256-268](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java)):

```java
public void terminate(
        @SuppressWarnings("unused") @Observes(notifyObserver = Reception.IF_EXISTS) @Priority(50) @BeforeDestroyed(ApplicationScoped.class) Object ignored) {
    for (IncomingRabbitMQChannel incoming : incomings) {
        incoming.terminate();
    }
    for (OutgoingRabbitMQChannel outgoing : outgoings) {
        outgoing.terminate();
    }
    clients.forEach((channel, rabbitMQClient) -> rabbitMQClient.stopAndAwait());
    clients.clear();
}
```

`incoming.terminate()` cancela a assinatura reativa direto, sem esperar nada
([`IncomingRabbitMQChannel.java` L260-263](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)):

```java
public void terminate() {
    Flow.Subscription sub = subscription.getAndSet(null);
    if (sub != null) {
        sub.cancel();
    }
}
```

Essa `cancel()` propaga para o consumidor RabbitMQ do Vert.x via `.onTermination().call(receiver::cancel)`
(mesmo arquivo, L249), e depois `clients.forEach(... rabbitMQClient.stopAndAwait())` fecha a
conexão **de forma bloqueante e incondicional** (`stopAndAwait`, ao contrário do
`stopAndForget` usado alhures no mesmo arquivo para descarte assíncrono). Uma mensagem que
esteja em voo — unacked, ffmpeg ainda rodando na worker pool — perde o canal nesse instante;
pela semântica do broker já citada na §5 acima, "any delivery that was not acked is
automatically requeued when the channel (or connection) on which the delivery happened is
closed". O ffmpeg pode continuar rodando na JVM depois disso (a `cancel()` não interrompe a
thread), mas o `ack()` que ele tentaria dar ao final cai num canal morto — e o RabbitMQ já
reenfileirou a mensagem muito antes disso, para o próximo consumidor que a receber (o
substituto do deploy). É exatamente a entrega que o ticket 030 queria evitar.

### Por que o grace period não protege esse instante

O `@BeforeDestroyed(ApplicationScoped.class)` acima dispara dentro de `Arc.shutdown()`, que o
`ArcRecorder` do Quarkus registra como uma tarefa de shutdown **simples** — a API antiga de
`ShutdownContext.addShutdownTask(Runnable)`, executada em `doStop()`
([`ArcRecorder.java` L54-57, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/extensions/arc/runtime/src/main/java/io/quarkus/arc/runtime/ArcRecorder.java)):

```java
shutdown.addShutdownTask(new Runnable() {
    @Override
    public void run() {
        Arc.shutdown();
    }
});
```

E `doStop()` só roda **depois** da fase graciosa e nova, de dois passos
(`ShutdownRecorder.runShutdown()`), não antes nem durante
([`Application.java` L221-223, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/core/runtime/src/main/java/io/quarkus/runtime/Application.java)):

```java
ShutdownRecorder.runShutdown();
doStop();
```

A fase graciosa (`preShutdown` → delay opcional → `shutdown`, com timeout) é orquestrada por
`ShutdownListener`s registrados separadamente — e **nenhum deles conhece Reactive
Messaging**. O único que existe no classpath do `extracao` e que conta trabalho em
andamento é o `GracefulShutdownFilter` do `quarkus-vertx-http` (trazido pelo
`quarkus-smallrye-health`, dono do `/q/health/ready` que o `docker-compose.yml` sonda), e ele
só sabe contar `HttpServerRequest`
([`GracefulShutdownFilter.java` L14-20, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/filters/GracefulShutdownFilter.java)):

```java
public class GracefulShutdownFilter implements ShutdownListener, Handler<HttpServerRequest> {
    ...
    private final AtomicInteger currentRequestCount = new AtomicInteger();
```

O próprio javadoc de `quarkus.shutdown.timeout` já avisa isso, e a leitura do código
confirma que "requests" aqui é literal, não uma metáfora para "trabalho pendente"
([`ShutdownConfig.java` L18-23, tag 3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/core/runtime/src/main/java/io/quarkus/runtime/shutdown/ShutdownConfig.java)):

> The timeout to wait for running requests to finish. If this is not set then the
> application will exit immediately.
> Setting this timeout will incur a small performance penalty, as it requires active
> requests to be tracked.

Uma Extração em voo não é uma `HttpServerRequest` — é um item consumido de um canal
`@Incoming` `@Blocking`, processado numa worker pool que nada aqui rastreia. Logo
`quarkus.shutdown.timeout` roda, não espera nada (nenhum listener conhece a Extração),
retorna, e só então `doStop()` chama `Arc.shutdown()`, que cancela a assinatura e fecha o
canal — **tipicamente em milissegundos após o `SIGTERM`**, porque não há nada no meio do
caminho que force uma espera. `stop_grace_period` do Docker só adia o `SIGKILL` que mataria o
processo inteiro; ele não atrasa em nada este instante, porque o instante já passou muito
antes do prazo do `stop_grace_period` sequer começar a importar.

### Veredito

A premissa do ticket 030 **não se sustenta como escrita**. `stop_grace_period` sozinho — por
maior que seja, mesmo acima do teto de `timeout-ffmpeg-segundos` — não entrega nada: o canal
já fechou e a mensagem já foi reenfileirada antes de o ffmpeg ter qualquer chance de
terminar. O `entrypoint` do `Dockerfile` do `extracao` já roda `java` como PID 1 (forma
`exec`, sem shell intermediário), então o `SIGTERM` chega direto na JVM — não é um problema
de sinal perdido ou mascarado, é a ordem de eventos dentro do próprio Quarkus. Para o Vídeo
chegar a `CONCLUIDO` sem gastar entrega num deploy, é preciso um mecanismo que **atrase o
início do desligamento gracioso até a Extração em voo terminar e dar ack** — o equivalente a
um `preStop` do Kubernetes, adaptado a Compose (que não tem hook de ciclo de vida
equivalente): candidatos incluem um `ShutdownListener` próprio que rastreia a Extração em
andamento e só libera `preShutdown`/`shutdown` quando ela termina (bounded por
`quarkus.shutdown.timeout`, que aí sim passaria a ter efeito), ou um processo de entrada que
intercepta o `SIGTERM` do Docker e só o encaminha à JVM depois de observar o canal livre. Os
dois mudam a forma do ticket original; nenhum é "configurar `stop_grace_period` e seguir em
frente". Desenho e medição ficam para um ticket novo, não para este.

---

## Fontes primárias

**Quarkus 3.31.3 (código e docs na tag)**
- [`quarkus-bom` 3.31.3](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.31.3/quarkus-bom-3.31.3.pom)
- [`docs/src/main/asciidoc/rabbitmq.adoc` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq.adoc)
- [`docs/src/main/asciidoc/rabbitmq-reference.adoc` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-reference.adoc)
- [`docs/src/main/asciidoc/rabbitmq-dev-services.adoc` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/rabbitmq-dev-services.adoc)
- [`docs/src/main/asciidoc/kafka.adoc` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/docs/src/main/asciidoc/kafka.adoc) (seção "Retrying processing")
- [`build-parent/pom.xml` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/build-parent/pom.xml) (`rabbitmq.image`)
- [`extensions/arc/runtime/.../ArcRecorder.java` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/extensions/arc/runtime/src/main/java/io/quarkus/arc/runtime/ArcRecorder.java) (adendo §8)
- [`core/runtime/.../Application.java` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/core/runtime/src/main/java/io/quarkus/runtime/Application.java) (adendo §8)
- [`core/runtime/.../shutdown/ShutdownRecorder.java` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/core/runtime/src/main/java/io/quarkus/runtime/shutdown/ShutdownRecorder.java) (adendo §8)
- [`core/runtime/.../shutdown/ShutdownConfig.java` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/core/runtime/src/main/java/io/quarkus/runtime/shutdown/ShutdownConfig.java) (adendo §8)
- [`extensions/vertx-http/runtime/.../GracefulShutdownFilter.java` @3.31.3](https://raw.githubusercontent.com/quarkusio/quarkus/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/filters/GracefulShutdownFilter.java) (adendo §8)
- [Guia Quarkus — SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance)

**SmallRye Reactive Messaging 4.32.1 (código e docs na tag)**
- [`RabbitMQConnector.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/RabbitMQConnector.java)
- [`internals/IncomingRabbitMQChannel.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/IncomingRabbitMQChannel.java)
- [`internals/RabbitMQClientHelper.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/RabbitMQClientHelper.java)
- [`internals/RabbitMQMessageSender.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/internals/RabbitMQMessageSender.java)
- [`IncomingRabbitMQMessage.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/IncomingRabbitMQMessage.java)
- [`fault/RabbitMQFailureHandler.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQFailureHandler.java) · [`RabbitMQReject.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQReject.java) · [`RabbitMQRequeue.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/smallrye-reactive-messaging-rabbitmq/src/main/java/io/smallrye/reactive/messaging/rabbitmq/fault/RabbitMQRequeue.java)
- [`docs/rabbitmq/rabbitmq.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/rabbitmq.md) · [`receiving-messages-from-rabbitmq.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/receiving-messages-from-rabbitmq.md) · [`sending-messages-to-rabbitmq.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/rabbitmq/sending-messages-to-rabbitmq.md)
- [`docs/concepts/acknowledgement.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/concepts/acknowledgement.md) · [`docs/concepts/blocking.md`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/docs/concepts/blocking.md)
- [`docs/rabbitmq/inbound/RabbitMQRejectMetadataExample.java`](https://raw.githubusercontent.com/smallrye/smallrye-reactive-messaging/4.32.1/documentation/src/main/java/rabbitmq/inbound/RabbitMQRejectMetadataExample.java)

**SmallRye Fault Tolerance 6.10.0**
- [Reference — Retry](https://smallrye.io/docs/smallrye-fault-tolerance/6.10.0/reference/retry.html)

**RabbitMQ (documentação oficial do broker)**
- [Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx)
- [Consumer Acknowledgements and Publisher Confirms](https://www.rabbitmq.com/docs/confirms)
- [Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues)
- [Time-To-Live and Expiration](https://www.rabbitmq.com/docs/ttl)
