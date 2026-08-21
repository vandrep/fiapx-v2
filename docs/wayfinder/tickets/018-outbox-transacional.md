# Transactional outbox no videos, ou conviver com o Vídeo órfão

- id: 018
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por:

## Question

O ticket 009 expôs um buraco e recusou-se a tapá-lo de afogadilho. `EnviarVideoUseCase` toca
três sistemas sem transação comum — MinIO, Postgres, RabbitMQ — na ordem objeto → linha →
publicação. Se o `videos` morrer entre o `INSERT` e o `publish`, sobra uma linha `RECEBIDO`
que **nunca vira nada**: sem comando não há extração, sem extração não há evento, e o
`x-delivery-limit` não protege uma mensagem que jamais entrou na fila. O usuário vê
`RECEBIDO` para sempre, e o `@Retry` do ADR 0001 só cobre a falha transitória do publish,
não o processo morto.

O mesmo buraco existe do outro lado: `RegistrarExtracaoFalhouUseCase` grava a transição e
publica `VideoFalhou` — morrer entre os dois perde a notificação de uma falha que já foi
registrada.

A decidir:

- **Adotar outbox transacional, ou registrar a limitação e seguir?** O custo é uma tabela, um
  poller e a mudança do `EventoRabbitDispatcher` para ler dela — provavelmente um dia. O
  benefício é fechar uma janela de milissegundos que a demo não produz, mas que a banca pode
  perguntar.
- Se **sim**: a tabela entra no `init.sql` do ticket 009 e o `%prod.validate` passa a exigi-la;
  o poller é `@Scheduled` (`quarkus-scheduler`) ou o Quarkus tem algo melhor? Como fica o
  teste de que a mensagem sai **uma** vez? A entrega vira *pelo menos uma vez*, o que já é o
  regime do ADR 0001 — o consumo é idempotente, então duplicar é inofensivo.
- Se **não**: em que documento a limitação fica registrada, e com que redação. O ADR 0001 é o
  lugar natural, como um "Consequências conhecidas".
- Existe meio-termo barato? Uma varredura periódica que reenfileira Vídeos parados em
  `RECEBIDO` há mais de N minutos resolve o caso do upload sem tabela nova — mas não resolve
  o `VideoFalhou` perdido.

**Contexto de prazo**: 5,5 semanas solo, e este ticket concorre diretamente com CI/CD e com o
roteiro do vídeo. Uma decisão defensável em duas frases vale mais aqui do que a solução
tecnicamente superior.
