# A falha da DLQ não chega ao Estacionamento

- id: 037
- label: wayfinder:bug
- status: fechado
- assignee:
- bloqueado-por:

## Question

Depois que o ticket 036 tornou os Dev Services alcançáveis no devcontainer rootless,
`ExtracaoEstacionamentoTest.publicacaoFalhaNoConsumoDaDlqChegaAoEstacionamento` chegou ao código
e reprovou: o consumidor registra que o `x-delivery-limit=3` foi esgotado, mas a mensagem não
aparece em `extracao.extrair.estacionamento` dentro dos 45 segundos do teste.

A falha ocorreu tanto em `./mvnw test` da raiz quanto ao executar apenas o teste. Ryuk,
RabbitMQ e LocalStack iniciaram normalmente nas duas execuções, portanto o sintoma não é a
conectividade de Dev Services investigada no 036.

## Condição de aceite

- [x] Diagnosticar por que a publicação rejeitada pelo canal `extracao-falhou` não chega ao
      Estacionamento.
- [x] Corrigir o comportamento ou a expectativa, conforme o contrato de mensagens e o ADR 0001.
- [x] Fazer `ExtracaoEstacionamentoTest` passar contra RabbitMQ real.
- [x] Executar `./mvnw test` da raiz sem falhas.

## Diagnóstico e correção

Reproduzido em 2026-09-05 com SmallRye Reactive Messaging 4.32.1, Vert.x 4.5.24 e
RabbitMQ 4.3.5. O comando abaixo chegou ao consumidor e falhou após 45 segundos, com
`mensagem deveria ter chegado ao estacionamento`. A porta aleatória evita a 8081 já ocupada
pelo Compose neste ambiente.

```sh
./mvnw -pl extracao -am -Dquarkus.http.test-port=0 \
  -Dtest=ExtracaoEstacionamentoTest -Dsurefire.failIfNoSpecifiedTests=false test
```

O broker registrou `operation basic.publish caused a channel exception not_found: no exchange
'extracao.eventos.inexistente' in vhost '/'`. Publicar em exchange inexistente fecha o canal;
não é um publisher `basic.nack` ([RabbitMQ: Publishers](https://www.rabbitmq.com/docs/4.2/publishers)).
Nos fontes dos JARs instalados, `RabbitMQPublisherImpl.handleConfirmation` conclui a promessa
do `publishConfirm` quando recebe confirmação. O fechamento do canal neste cenário deixa a
promessa pendente. `RabbitMQMessageSender.send` só retenta e chama `msg.nack` quando aquela
operação conclui com falha. Logo, `publish-confirms=true` é necessário, mas não basta para
garantir um prazo de desfecho. Repetir com `retry-on-fail-attempts=0` produziu o mesmo timeout
de 45 segundos: não era o backoff. A topologia estava correta e não precisou mudar.

`RabbitExtracaoEventosSender.enviarFalhou` agora limita a espera total por
`fiapx.extracao.timeout-publicacao-falha-segundos=30`. Esse teto inclui retentativas e espera
por confirmação, acomodando o backoff usual de aproximadamente 20–25 segundos. Quando vence,
o `CompletableFuture` falha e o consumidor da DLQ aplica o `reject` já configurado, levando o
comando ao Estacionamento. O mesmo envio é usado pela falha permanente na fila de trabalho:
ali a falha segue o `requeue` existente até esgotar as entregas e chegar à DLQ.

Timeout significa **ausência de confirmação no prazo**, não prova de recusa pelo broker.
Não desfaz publicação já enviada nem garante cancelamento no cliente; um evento pode chegar
tarde e coexistir com o comando estacionado. A transição idempotente em `videos` continua
tratando duplicatas. O teto protege a conclusão da entrega; não repara o canal AMQP fechado
nem os recursos internos de confirmações pendentes do cliente. O escopo são publicações de
`ExtracaoFalhou`; os outros dois eventos não receberam mudanças.

O teste original passou com o teto de produção de 30 segundos e agora também confere que o
corpo recebido no Estacionamento é exatamente o comando enviado.

Validação da raiz: `QUARKUS_HTTP_TEST_PORT=0 ./mvnw test` passou com 392 testes
(105 em `videos`, 263 em `extracao`, 24 em `notificacao`), sem falhas, erros ou skips.
A variável só evita a colisão com a porta 8081 da stack local. Revisão em dois eixos contra
`1171f2f4b2bab6f605a8a73ae9ce1855fb6fb130`: nenhum achado de padrões ou especificação.

`scripts/smoke.sh` também passou contra o Compose local, com `extracao` empacotado a partir
desta correção (`./mvnw -pl extracao -am -DskipTests package`) e imagem local
`fiapx-extracao:ticket037` selecionada por overlay temporário: processamento, download,
notificação de falha e isolamento entre donos. O overlay não altera os arquivos do Compose.
