# Drenar a Extração em voo antes do `extracao` receber `SIGTERM`

- id: 035
- label: wayfinder:research
- status: aberto
- assignee: agente de implementacao (sessao de 2026-09-04)
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

## Resolução parcial

**Mecanismo escolhido: nenhum dos dois esboçados.** O caminho 1 (`ShutdownListener` próprio) é
impossível como escrito: a lista de listeners vem de `ShutdownListenerBuildItem`, um build item
de *augmentation* que embrulha uma instância criada em tempo de build, e produzi-lo exige uma
extensão Quarkus com módulo de deployment — não há caminho por bean CDI, e um
`@ApplicationScoped implements ShutdownListener` é ignorado sem aviso. O caminho 2 (`tini`/`trap`)
ficou na gaveta: o `entrypoint` já é `exec` form com `java` como PID 1, então o `SIGTERM` chega
direto na JVM e não há sinal perdido a consertar; um wrapper faria fora do processo o que dá
para fazer dentro, e reabriria a pergunta de encaminhamento de sinal de propósito fechada.

O que foi implementado é o `DrenoDaExtracao`: um observador do **mesmo evento CDI que o
conector observa** — `@BeforeDestroyed(ApplicationScoped.class)` — com `@Priority(10)` contra o
`@Priority(50)` do `RabbitMQConnector.terminate()`. A ordenação por prioridade é garantia da
especificação CDI, então o dreno roda imediatamente antes de o canal fechar, e não com a fase
graciosa de HTTP inteira no meio (que é o que qualquer solução em `preShutdown` teria).
Confirmado o que o ticket pediu para confirmar: `preShutdown.await()` é mesmo incondicional, e
`quarkus.shutdown.timeout` só limita a fase `shutdown()` — que já passou quando o dreno roda.
Por isso o teto é da aplicação (`fiapx.extracao.dreno-timeout-segundos=420`) e o
`application.properties` **não** declara `quarkus.shutdown.timeout`: declará-lo sugeriria um
efeito que ele comprovadamente não tem.

Os três números ficaram coerentes: 330s de teto duro de processo externo (ffprobe + ffmpeg),
420s de teto do dreno, 480s de `stop_grace_period` no Compose — cada um estritamente maior que
o anterior, para o Docker nunca ganhar a corrida do JVM. O ack virou **manual** no
`ExtrairVideoConsumer`, porque com o ack implícito o `Uni` completa antes de o pipeline ackear e
o dreno seria liberado com o ack em voo — e ackear num canal já fechado não poupa entrega
nenhuma.

Achado completo, com o código-fonte do classpath citado por linha, em
[`docs/pesquisa/rabbitmq-retry-dlq.md` §9](../../pesquisa/rabbitmq-retry-dlq.md).

### A medição, e o critério que ela reprovou

Modo novo `redeploy-extracao` no `scripts/carga/conservacao.sh`, com portão de validade próprio
(a rodada só vale se houver Extração em voo comprovada no momento do `SIGTERM` — a primeira
tentativa recriou réplicas ociosas e teria passado verde sem julgar nada) e captura do log das
réplicas que vão morrer, porque `--force-recreate` remove o container e depois dele não há log a
ler. Duas rodadas válidas, 2 réplicas, host de 6 núcleos:

| | 250 envios de `controle-3s.mp4` | 8 envios de `carga-2min.mp4` |
|---|---|---|
| Extrações em voo no `SIGTERM` | 2 | 2 |
| réplicas que registraram o dreno | 2 de 2 | 2 de 2 |
| **quanto o dreno segurou** | 20 ms e 26 ms | **2,5 s e 2,6 s** |
| `up -d --force-recreate` | 3s | 5s |
| Vídeos em estado terminal | 250/250, zero `FALHOU` | 8/8, zero `FALHOU` |
| **reentregas novas** | **2 — o critério pedia 0** | **1 — o critério pedia 0** |

A segunda coluna existe porque a primeira não provava o que dizia provar: com o fixture de
controle, a Extração em voo já estava no ack quando o sinal chegou, e um dreno que segura por
20 ms não demonstra nada sobre segurar um `ffmpeg`. Com o fixture de 2 min o dreno segurou
**segundos**, e é essa a evidência de que ele bloqueia o desligamento pelo trabalho que resta.
O quanto ele segura é, por construção, o que sobra da Extração — de milissegundos ao teto —,
então nenhuma rodada aqui exercitou os 420s, e isso continua não medido.

**O critério de aceite deste ticket não foi cumprido, e não vou dizer que foi.** O dreno roda,
está provado que roda, e a Extração em voo chega ao fim em vez de ser destruída — mas a
contagem de entregas subiu igual — 2 numa rodada, 1 na outra, ou seja **até uma por réplica**,
conforme o broker consiga ou não empurrar a mensagem seguinte antes de a conexão fechar. A causa
está medida: o ack que libera o
dreno é o mesmo que devolve o crédito do `max-outstanding-messages=1`, então o broker entrega a
mensagem seguinte na janela entre o dreno liberar e a conexão fechar. Ela volta para a fila sem
nunca ter chegado ao consumidor — o log não tem uma linha sequer de portão fechado, o que
confirma que ela não foi vista. Fechar isso depende de `basic.cancel` por canal, que o conector
4.32.1 tem (`IncomingRabbitMQChannel.terminate()`) mas não expõe. É o que falta,
e está logo abaixo.

O que mudou, então, é **onde** a entrega é gasta, não quantas: ela deixou de recair sobre a
Extração em voo e passou a recair sobre uma mensagem que não começou trabalho nenhum. Some o
viés sistemático contra Vídeos longos — que eram os expostos a um deploy, e portanto os
candidatos a colecionar três entregas e cair na DLQ. É uma troca boa; não é a promessa do 030.

## O que falta, e por que este ticket continua aberto

O critério de aceite é o número, e o número não veio. O que fecharia a janela é a sequência
clássica de drenagem — **`basic.cancel` primeiro, ack depois, fechamento por último**. Com a
assinatura cancelada o broker para de entregar, mas o canal continua aceitando o ack da
mensagem já entregue. O conector 4.32.1 **tem** essa operação — `IncomingRabbitMQChannel.terminate()`
cancela só a assinatura, e ela propaga para `receiver::cancel` — mas não a expõe: `incomings` é
`private` e sem acessor, e o único método público, `RabbitMQConnector.terminate()`, é
tudo-ou-nada e já fecha a conexão junto (`clients.forEach(... stopAndAwait())`).

Três caminhos, nenhum decidido:

1. **Reflexão** sobre o campo `incomings`, para cancelar só a assinatura antes de liberar o
   dreno. É a única opção que não depende de terceiros — ao custo de prender o serviço a um
   campo privado de uma dependência, que uma atualização de versão quebra em silêncio. Precisa
   também confirmar se o `sub.cancel()` da assinatura reativa não derruba a mensagem que está
   sendo processada, o que anularia o ganho.
2. **Upstream**: um `pause()`/`cancel()` por canal no `smallrye-reactive-messaging-rabbitmq`. É
   o lugar certo do conserto, e o mais lento.
3. **Aceitar e documentar**, que é o estado de hoje
   ([`docs/arquitetura.md` § Limitações conhecidas](../../arquitetura.md#limitações-conhecidas)).

Fecha quando `scripts/carga/conservacao.sh redeploy-extracao` der o critério 6 verde numa
rodada válida.

## Encontrado no caminho

O harness de carga não subia. Duas variáveis do `docker-compose.carga.yml`, **com os valores
default inclusive**, derrubavam o boot do `extracao` em `SRMSG00071` — o traço de
`extracao-falhou` não sobrevive à conversão para variável de ambiente, e o SmallRye deduz da
enumeração um canal `extracao` sem `connector`. Isso atingia os quatro modos, não só o
`mata-publicacao`, e nunca tinha aparecido porque aquele modo nunca rodou contra o Compose.
Desbloqueado o mínimo (forma de lista, variável só existe quando o harness a exporta);
o resto está no [038](038-override-de-canal-por-variavel-quebra-o-boot.md). O `k6` também não
escrevia a saída sob Docker rootless, e o `--user` passou a ser derivado do daemon em vez de
fixado.
