# Implementação do serviço videos: mensageria e máquina de estados

- id: 017
- label: wayfinder:task
- status: fechado
- assignee: vandrep
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

## Resolução

**82 testes verdes, 63 sem Docker** — o `core` inteiro (as três transições, o envio e a
varredura de reconciliação) roda com dublês em memória; só o `CucumberTest` (19) sobe
Postgres/MinIO/Keycloak/RabbitMQ. Implementado test-first como os demais tickets de
implementação: `VideoGateway` ganhou `marcarIniciada`/`marcarConcluida` (booleano — "mudou a
linha?") e `marcarFalha` (devolve o `Video` preenchido só quando mudou, porque é dali que o
evento `VideoFalhou` tira dono/e-mail/nome — dispensa um `buscarPorId` sem dono, que o
ticket 009 proibiu estruturalmente), mais `marcarComandoPublicado`/`marcarFalhaPublicada` e
`buscarComandosPendentes`/`buscarFalhasPendentes` para o outbox. Duas interfaces novas em
`core.interfaces.sender` (`ExtracaoSender`, `NotificacaoSender`) espelham o gateway: o
`core` fala em tipos de domínio, e quem monta o `record` do contrato é o adapter em
`framework.dispatcher`.

Três achados que a especificação não tinha como prever:

- **`EstadoVideo.transitaPara` tinha um bug real**, descoberto pelo próprio teste que o
  ticket pediu. A guarda "terminal para terminal é bug" barrava também terminal-para-**si
  mesmo** — `FALHOU.transitaPara(FALHOU)` lançava `IllegalStateException` em vez de devolver
  `false`, porque o `this != destino` nunca tinha sido testado (só existia o par
  FALHOU↔CONCLUIDO, gravado desde o ticket 009). Resultado: a segunda e a terceira entrega do
  mesmo `ExtracaoFalhou` — o próprio cenário que a política de falhas existe para tolerar —
  derrubariam a mensagem em vez de dar ack. Corrigido comparando identidade antes da checagem
  de terminal; ficou registrado como teste em `EstadoVideoTest`.
- **A varredura de reconciliação corrompia a sessão reativa do Hibernate quando rodava em
  paralelo.** `Panache.withSession`/`withTransaction` reusam uma sessão por contexto do
  Vert.x; a primeira versão do use case disparava a consulta de comandos pendentes e a de
  falhas pendentes ao mesmo tempo (`thenCombine`) e, dentro de cada uma, processava o lote
  inteiro em paralelo (`CompletableFuture.allOf`). Medido em `quarkus dev` com uma linha
  pendente de verdade: `NoSuchElementException` em `LoadContexts`/`StandardStack.pop` do
  Hibernate Reactive, porque duas queries concorrentes na mesma sessão corrompem sua pilha
  interna. A varredura roda **em sequência** agora — uma consulta, depois a outra, um Vídeo
  de cada vez — o que é mais simples e ainda tolera duas réplicas varrendo ao mesmo tempo
  (ADR 0003 já aceitava isso; só não previa concorrência *dentro* de uma varredura).
- **`auto-bind-dlq=true` sozinho não declara a dead-letter exchange.** O conector
  `smallrye-rabbitmq` só declara a DLX quando `dlx.declare=true` também está presente —
  sem isso, o boot falhava com `NOT_FOUND - no exchange 'DLX' in vhost '/'` assim que a
  primeira mensagem chegava. Corrigido nos três canais de entrada, com a DLX nomeada
  `videos.dlx` em vez do default `DLX`.

O `@Scheduled` também precisou devolver `Uni<Void>`, não `void`: um método `void` despacha
no worker pool, e a primeira chamada ao Panache falhava com "should exclusively be invoked
from a Vert.x EventLoop thread" — devolver `Uni` faz o scheduler despachar no event loop.

Topologia verificada de verdade (não assumida): subida manual com `quarkus dev`, um
`POST /videos` de ponta a ponta contra Keycloak/MinIO/Postgres/RabbitMQ reais, e inspeção da
API de management do RabbitMQ confirmou as três filas quorum com `x-delivery-limit=3`, a DLQ
compartilhada `videos.dlq` com uma *binding* por fila de origem, e `comando_publicado_em`
gravado no Postgres após o publish. Descoberta lateral: os canais de saída
(`extrair-video`, `video-falhou`, via `MutinyEmitter`) só declaram sua exchange na **primeira
mensagem enviada** — diferente dos canais de entrada, que já assinam no boot. Sem efeito
prático aqui (o publish sempre acontece antes de qualquer verificação), mas explica por que
`fiapx.comandos` não aparecia na API de management logo depois do boot.

`quarkus.rabbitmq.devservices.image-name` fixa a imagem dos Dev Services em
`rabbitmq:4.3.5-management-alpine`, igual à cartografia do mapa — sem isso os Dev Services
escolhem a imagem management mais recente sozinhos.
