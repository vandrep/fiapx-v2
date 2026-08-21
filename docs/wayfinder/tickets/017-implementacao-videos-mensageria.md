# Implementação do serviço videos: mensageria e máquina de estados

- id: 017
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 016, 018

## Question

A metade assíncrona do `videos`, e o coração da política de falhas: é aqui que mora a
guarda de unicidade do e-mail.

Implementar, test-first, conforme
[`docs/contratos/mensagens.md`](../../contratos/mensagens.md) e o
[ADR 0001](../../adr/0001-politica-de-falhas.md):

- Publicação de `ExtrairVideo` após o upload, com as chaves de MinIO já montadas — o
  `extracao` não conhece a convenção de nomes. A marca `comando_publicado_em` é gravada
  **depois** do publish ([ADR 0003](../../adr/0003-reconciliacao-por-varredura.md)).
- Consumo de `ExtracaoIniciada`, `ExtracaoConcluida` e `ExtracaoFalhou` em
  `framework.dispatcher`, sem regra no consumidor: monta command, chama controller.
- As transições idempotentes por `UPDATE ... WHERE id = ? AND estado = ?`, e a publicação
  de `VideoFalhou` **apenas** quando a linha de fato mudou. Um evento reentregue fora de
  ordem não pode reanunciar nada.
- `max-outstanding-messages=20`, `failure-strategy=requeue`, filas quorum com
  `x-delivery-limit=3`, DLQ compartilhada `videos.dlq` (terminal).
- `@JsonIgnoreProperties(ignoreUnknown = true)` em todo consumidor (tolerant reader).
- Regra no `ArchitectureConstraintsTest`: `@Incoming`/`@Outgoing`/`@Scheduled` só em
  `framework`.
- **Varredura de reconciliação** (ADR 0003), que o ticket 018 pousou aqui:
  `ReconciliarPublicacoesPendentesUseCase` no `core`, com o `@Scheduled(every="30s")` em
  `framework` como gatilho burro — monta command, chama controller, nenhuma regra. Republica
  Vídeos `RECEBIDO` com `comando_publicado_em` nulo há mais de 1 minuto e Vídeos `FALHOU` com
  `falha_publicada_em` nulo, lote de 100, `ORDER BY recebido_em`, chamando **o mesmo** método
  de gateway que o caminho normal chama. Sem `SKIP LOCKED` e sem eleição de líder: duas
  réplicas varrendo é aceito. Exige a extensão `quarkus-scheduler`, que o esqueleto do ticket
  002 não trouxe.

Os testes que importam: três entregas do mesmo `ExtracaoFalhou` produzem **um** `VideoFalhou`;
e, no `core` sem broker, uma linha pendente e velha é republicada e marcada, **a segunda
passada não publica nada**, e uma linha já marcada não é tocada por mais velha que seja.
