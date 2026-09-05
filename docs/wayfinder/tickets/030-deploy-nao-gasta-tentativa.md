# Deploy não gasta tentativa da Extração

- id: 030
- label: wayfinder:research
- status: fechado
- assignee: agente de pesquisa (sessao de 2026-09-04)
- bloqueado-por:

## Question

Uma Extração interrompida por deploy não é falha do Vídeo, mas hoje custa uma das três
entregas. Três deploys que peguem o mesmo Vídeo em voo o mandam para a DLQ, e o Dono recebe
um e-mail de `TENTATIVAS_ESGOTADAS` cuja causa foi rolling restart, não o arquivo dele.

Não é azar: é **determinístico**. O `docker-compose.yml` não declara `stop_grace_period` no
serviço `extracao`, e o default do Docker é **10 segundos** até o `SIGKILL`. O trabalho em voo
tem teto de minutos — `timeout-ffprobe-segundos=30` e `timeout-ffmpeg-segundos=300`, mais
download e upload. Todo deploy que pegue uma Extração em voo a mata.

A boa notícia é o contorno ser barato: `max-outstanding-messages=1` no canal `extrair-video`
limita cada réplica a **uma** Extração em voo, então a janela a proteger é uma só e é limitada
por cima pela própria configuração.

## O que precisa ser provado antes de configurar

`stop_grace_period` acima do teto do trabalho em voo só resolve **se** o conector parar de
puxar mensagem no `SIGTERM` e deixar a que está em voo terminar e dar ack. Isso não está
verificado, e é a pergunta central do ticket, não um detalhe de implementação: se o
smallrye-reactive-messaging-rabbitmq 4.32.1 cancelar a assinatura derrubando a mensagem em
voo, o grace period sozinho não entrega nada e o ticket muda de forma — provavelmente para um
`preStop` que espera o canal drenar antes de o processo receber o sinal.

Mesma disciplina do [003](003-rabbitmq-retry-dlq.md): a fonte é o código do conector na versão
que está no classpath, não a documentação genérica — foi assim que o `publish-confirms=false`
por default apareceu, lido no `@ConnectorAttribute` e citado pelo
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md). O achado desta pergunta é adendo a
[`docs/pesquisa/rabbitmq-retry-dlq.md`](../../pesquisa/rabbitmq-retry-dlq.md), que já é o lugar
onde o comportamento deste conector mora.

## O que muda, se a prova passar

- `stop_grace_period` no serviço `extracao` do `docker-compose.yml`, acima do teto de relógio
  de uma Extração;
- `quarkus.shutdown.timeout` coerente com ele, para o Quarkus não desistir antes do Docker;
- uma linha em `docs/arquitetura.md` explicando o número, porque um grace period de minutos
  parece exagero para quem não conhece o teto do ffmpeg.

## Condição de aceite

Subir carga, derrubar o `extracao` no meio de uma Extração com `docker compose up -d`, e
mostrar que o Vídeo chega a `CONCLUIDO` sem que a contagem de entregas da mensagem tenha
subido. Sem esse número o ticket fecha sem ter provado nada.

## O que este ticket não conserta

A janela não vai a zero: `SIGKILL` por OOM, morte do nó ou queda de rede continuam gastando
entrega. Zerar exigiria tentativa durável no `extracao` — banco próprio —, e essa porta está
fechada por decisão desta rodada e pelo `AGENTS.md`. O ticket reduz o caso **frequente e
evitável**; o raro continua coberto pelo `x-delivery-limit` e pelo [029](029-terminal-na-dlq-do-extracao.md).

## Resolução

A pergunta central tem resposta, e é **não**: o smallrye-reactive-messaging-rabbitmq 4.32.1
não espera nenhuma mensagem em voo. `RabbitMQConnector.terminate()` observa
`@BeforeDestroyed(ApplicationScoped.class)` e cancela a assinatura + fecha a conexão de forma
síncrona e incondicional, sem checar trabalho em andamento. Esse evento dispara dentro de
`Arc.shutdown()`, que roda em `doStop()` — **depois** da fase graciosa nova do Quarkus
(`ShutdownRecorder.runShutdown()`, a que `quarkus.shutdown.timeout` se aplica), mas nenhum
`ShutdownListener` no classpath do `extracao` conhece Reactive Messaging: o único que existe
(`GracefulShutdownFilter`, do `quarkus-vertx-http` trazido pelo health check) só conta
`HttpServerRequest`. Resultado: o canal fecha e a mensagem é reenfileirada em milissegundos
após o `SIGTERM`, independente do tamanho do `stop_grace_period` — o grace period só adia o
`SIGKILL` que mataria o processo inteiro, não protege esse instante, que já passou muito
antes. Achado completo, com código-fonte citado por linha, em
[`docs/pesquisa/rabbitmq-retry-dlq.md` §8](../../pesquisa/rabbitmq-retry-dlq.md#8-adendo-ticket-030-o-conector-espera-a-mensagem-em-voo-terminar-no-sigterm).

Como a premissa não se sustenta, nada do "O que muda, se a prova passar" foi implementado —
configurar `stop_grace_period` sozinho não entregaria o que o ticket prometia, e fazer isso
sem a prova seria exatamente o erro que a seção "O que precisa ser provado antes de
configurar" queria evitar. A forma do problema mudou, como o próprio ticket antecipou: falta
um mecanismo que atrase o início do desligamento gracioso até a Extração em voo terminar e
dar ack — o equivalente a um `preStop`, que o Compose não tem nativamente. Desenho e medição
(carga + `docker compose up -d` no meio de uma Extração + contagem de entregas) ficam para o
[ticket 035](035-drenar-extracao-antes-do-sigterm.md), aberto nesta mesma sessão.
