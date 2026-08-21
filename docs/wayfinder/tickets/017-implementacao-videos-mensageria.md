# Implementação do serviço videos: mensageria e máquina de estados

- id: 017
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 016

## Question

A metade assíncrona do `videos`, e o coração da política de falhas: é aqui que mora a
guarda de unicidade do e-mail.

Implementar, test-first, conforme
[`docs/contratos/mensagens.md`](../../contratos/mensagens.md) e o
[ADR 0001](../../adr/0001-politica-de-falhas.md):

- Publicação de `ExtrairVideo` após o upload, com as chaves de MinIO já montadas — o
  `extracao` não conhece a convenção de nomes.
- Consumo de `ExtracaoIniciada`, `ExtracaoConcluida` e `ExtracaoFalhou` em
  `framework.dispatcher`, sem regra no consumidor: monta command, chama controller.
- As transições idempotentes por `UPDATE ... WHERE id = ? AND estado = ?`, e a publicação
  de `VideoFalhou` **apenas** quando a linha de fato mudou. Um evento reentregue fora de
  ordem não pode reanunciar nada.
- `max-outstanding-messages=20`, `failure-strategy=requeue`, filas quorum com
  `x-delivery-limit=3`, DLQ compartilhada `videos.dlq` (terminal).
- `@JsonIgnoreProperties(ignoreUnknown = true)` em todo consumidor (tolerant reader).
- Regra no `ArchitectureConstraintsTest`: `@Incoming`/`@Outgoing` só em `framework`.

O teste que importa: três entregas do mesmo `ExtracaoFalhou` produzem **um** `VideoFalhou`.
