# A falha da DLQ não chega ao Estacionamento

- id: 037
- label: wayfinder:bug
- status: aberto
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

- [ ] Diagnosticar por que a publicação rejeitada pelo canal `extracao-falhou` não chega ao
      Estacionamento.
- [ ] Corrigir o comportamento ou a expectativa, conforme o contrato de mensagens e o ADR 0001.
- [ ] Fazer `ExtracaoEstacionamentoTest` passar contra RabbitMQ real.
- [ ] Executar `./mvnw test` da raiz sem falhas.
