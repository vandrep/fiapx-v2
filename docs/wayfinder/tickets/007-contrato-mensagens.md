# Contrato de mensagens entre videos, extracao e notificacao

- id: 007
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 003, 006, 010

## Question

Sem módulo `shared`, o contrato de mensagens é a fronteira real entre os três serviços —
e cada um declara sua própria cópia. Isso só funciona se o contrato for decidido
explicitamente e documentado num lugar só.

A decidir:

- Topologia no RabbitMQ: exchanges, filas, routing keys, DLQs, e quem declara o quê.
- Quais mensagens existem: o comando de extração e os eventos de progresso, conclusão e
  falha. Nomes canônicos, em português, alinhados ao `CONTEXT.md`.
- Payload de cada mensagem: campos, tipos, e o que **não** entra (o vídeo em si nunca
  trafega — só a chave do objeto no MinIO).
- Identidade e correlação: o que amarra evento a Vídeo, e como isso serve de chave de
  idempotência quando o RabbitMQ redelivera.
- Como o evento de **falha definitiva** (o que aciona o e-mail) se distingue de uma falha
  de tentativa — o usuário não pode receber três e-mails.
- Versionamento: o que acontece quando um campo precisa mudar, dado que há três cópias do
  contrato.
- Onde o contrato vive documentado para as três cópias não divergirem.

Este ticket gradua boa parte da névoa de implementação — espere criar tickets novos ao
fechá-lo.
