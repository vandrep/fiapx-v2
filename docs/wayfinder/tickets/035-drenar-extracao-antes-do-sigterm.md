# Drenar a Extração em voo antes do `extracao` receber `SIGTERM`

- id: 035
- label: wayfinder:research
- status: aberto
- assignee:
- bloqueado-por: 030

## Question

O [030](030-deploy-nao-gasta-tentativa.md) provou, contra o código-fonte do
smallrye-reactive-messaging-rabbitmq 4.32.1 e do `quarkus-arc`/`quarkus-vertx-http` 3.31.3
(achado em [`docs/pesquisa/rabbitmq-retry-dlq.md` §8](../../pesquisa/rabbitmq-retry-dlq.md#8-adendo-ticket-030-o-conector-espera-a-mensagem-em-voo-terminar-no-sigterm)),
que `stop_grace_period` sozinho não resolve nada: o conector cancela a assinatura e fecha o
canal de forma síncrona e incondicional assim que `@BeforeDestroyed(ApplicationScoped.class)`
dispara, e isso acontece em milissegundos após o `SIGTERM` — muito antes de qualquer grace
period importar, e sem que `quarkus.shutdown.timeout` participe (ele só espera
`HttpServerRequest`, via `GracefulShutdownFilter`; Reactive Messaging não é rastreado por
nada equivalente).

Esta pergunta é a que o 030 antecipou como provável desdobramento: precisa de um mecanismo
que **atrase o início do desligamento gracioso do Quarkus até a Extração em voo terminar e
dar ack**, para então deixar o `SIGTERM`/CDI seguir seu curso normal. Dois caminhos foram
esboçados no 030, nenhum decidido nem medido:

1. **`ShutdownListener` próprio.** Um bean que implementa `preShutdown`/`shutdown`
   (`io.quarkus.runtime.shutdown.ShutdownListener`), registrado do mesmo jeito que o
   `GracefulShutdownFilter`, que marca "Extração em andamento" antes de chamar `ffmpeg` e só
   chama `notification.done()` quando ela termina (ack incluído). Isso faria
   `quarkus.shutdown.timeout` finalmente ter efeito sobre a Extração — ele limita a fase
   `shutdown()`, então esse é o timeout que passaria a valer como teto real, coerente com
   `timeout-ffprobe-segundos` + `timeout-ffmpeg-segundos`. Precisa confirmar, lendo o
   `ShutdownRecorder` de novo: `preShutdown` não tem timeout (`preShutdown.await()` é
   incondicional) — se a Extração ainda estiver rodando quando o `docker stop` estourar o
   próprio `stop_grace_period` (não o do Quarkus), o Docker manda `SIGKILL` de qualquer jeito
   e a espera foi inútil. As duas contagens (`quarkus.shutdown.timeout` e
   `stop_grace_period` do Compose) precisam ficar coerentes entre si e com o teto de relógio
   da Extração — três números, não um.
2. **Processo de entrada que intercepta o `SIGTERM`.** Um `tini`/script com `trap`, PID 1 no
   lugar do `java -jar` direto, que segura o sinal até um arquivo/endpoint de "canal livre"
   aparecer e só então mata o processo Java. Mais próximo do `preStop` do Kubernetes, mas o
   `entrypoint` do `Dockerfile` do `extracao` hoje é `exec` direto (`ENTRYPOINT ["java",
   "-jar", ...]`, ticket 006/015) — trocar por um wrapper reabre a pergunta de encaminhamento
   de sinal que o `exec` form evitava de propósito.

Falta decidir qual, medir se o escolhido realmente funciona (o 030 já mostrou que "parece
que devia funcionar" não é prova nesta stack), e só então mexer em
`docker-compose.yml`/`application.properties`/`docs/arquitetura.md`.

## Condição de aceite

A mesma do 030, porque é a mesma pergunta de fundo, agora contra o mecanismo escolhido: subir
carga, derrubar o `extracao` no meio de uma Extração com `docker compose up -d`, e mostrar que
o Vídeo chega a `CONCLUIDO` sem que a contagem de entregas da mensagem tenha subido. Medir
também o tempo total do `docker compose up -d` (o grace period cobrado por deploy tem custo
de janela de indisponibilidade da réplica, mesmo com N réplicas — relevante para o
[028](028-escala-da-borda.md)).

## O que este ticket não conserta

Mesma fronteira do 030: `SIGKILL` por OOM, morte do nó ou queda de rede continuam gastando
entrega, e zerar isso exigiria tentativa durável no `extracao`, porta fechada pelo
`AGENTS.md`.
