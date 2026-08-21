<!-- label: wayfinder:map -->
# Mapa: FIAP X — processamento de vídeos em Quarkus

## Destino

Entregar o Hackathon FIAP X até **29/09/2026**: o processador de vídeos do projeto base
(Go, monolito síncrono) reescrito como **três serviços Quarkus em Clean Architecture num
único repositório**, rodando em Docker Compose, com upload autenticado, processamento
assíncrono resiliente, listagem de status por usuário, download do ZIP e notificação de
erro — acompanhado da documentação de arquitetura, do script de banco, do repositório no
GitHub com CI/CD e do roteiro do vídeo de até 10 minutos.

Este mapa carrega **decisões e execução**: as decisões de arquitetura vêm primeiro, e os
tickets de implementação graduam da névoa conforme cada decisão fecha.

## Notas

**Domínio**: processamento de vídeo — o usuário envia um **Vídeo**, o sistema executa uma
**Extração** de frames (ffmpeg, 1 fps) e devolve um **Pacote** (.zip). Vocabulário
canônico em [`CONTEXT.md`](../../CONTEXT.md).

**Skills que toda sessão deve consultar**: `grilling` e `domain-modeling` por padrão;
`research` para tickets de pesquisa; `tdd` nos tickets de implementação (o template já é
test-first por construção); `writing-for-agents` ao editar `AGENTS.md`.

**Restrições fixadas na cartografia** (premissas do esforço, não decisões de tickets):

| Decisão | Escolha |
|---|---|
| Prazo / equipe | 29/09/2026, uma pessoa (~5,5 semanas a partir de 20/08/2026) |
| Alvo de deploy | Docker Compose |
| Interface de usuário | Nenhuma — demo via Swagger UI e `curl` |
| Serviços | `videos`, `extracao`, `notificacao` (+ Keycloak como infra) |
| Layout | Maven multi-módulo com parent agregador, **sem** módulo `shared` |
| Package base | `br.com.fiapx.<servico>`; artefatos `fiapx`, `fiapx-videos`, `fiapx-extracao`, `fiapx-notificacao` |
| Mensageria | RabbitMQ (SmallRye Reactive Messaging) |
| Armazenamento de arquivos | MinIO (S3-compatível); serviços trocam chaves de objeto |
| Banco | Um Postgres, um database por serviço que precise — na prática **só `videos`** |
| Dono do status | `videos` é o dono; `extracao` é worker sem estado que publica eventos |
| Estados do Vídeo | `RECEBIDO` → `PROCESSANDO` → `CONCLUIDO` \| `FALHOU` |
| Falhas | Fila quorum, `x-delivery-limit=3` (conta **entregas**, crash incluído), `failure-strategy=requeue`, `@Retry` com backoff de segundos nos adapters de I/O. `extracao` consome a própria DLQ; DLQs de `videos` e `notificacao` são terminais. Unicidade da notificação na transição de estado em `videos`. E-mail é *pelo menos uma vez*. Ver [ADR 0001](../adr/0001-politica-de-falhas.md) |
| RabbitMQ | `rabbitmq:4.3.5-management-alpine` fixado nos Dev Services e no Compose; policy `dead-letter-strategy=at-least-once` só no Compose (é policy de broker, não queue argument) |
| Autenticação | Keycloak, bearer-only via `quarkus-oidc`; dono do vídeo vem do `sub` do token, nunca do request |
| Notificação | SMTP com MailHog no Compose |
| Health checks | Sim (`quarkus-smallrye-health`), para `depends_on: service_healthy` |
| CI/CD | GitHub Actions: `verify` + build das imagens + push para o GHCR com tag do commit |
| Testes | Por serviço e isolado (unitário do `core`, Cucumber pela borda HTTP, `ArchitectureConstraintsTest`); fluxo ponta-a-ponta por script de smoke versionado, não automatizado no CI |

**Base de código**: template em `/home/vandrep/projetos/oficina-soat/quarkus-clean-architecture-template`
(leia o `AGENTS.md` dele antes de escrever qualquer classe — as regras de camada são
verificadas por teste, não são sugestão). Projeto original em
`docs/referencia/referencia/projeto-original/main.go`. Enunciado em `docs/enunciado.md`.

## Decisões até aqui

<!-- uma linha por ticket fechado -->

- [Keycloak bearer-only com quarkus-oidc](tickets/004-oidc-keycloak.md) — o Resource injeta
  `JsonWebToken` e passa `getSubject()` como `String` ao controller (nunca `getName()`, que
  é `upn`); Dev Services for Keycloak roda em `@QuarkusTest` e compartilha o
  `realm-export.json` com o Compose, desde que `auth-server-url` fique só sob `%prod.`
- [Upload multipart reativo e cliente MinIO/S3](tickets/005-upload-download-minio.md) —
  `S3AsyncClient` + `netty-nio-client`, streaming ponta a ponta (`fromFile`/`toPublisher`/`toFile`,
  nunca `toBytes`); `max-body-size` default de 10 MB precisa ser elevado; presigned URL é
  viável mas é *bearer token* e o host entra na assinatura
- [Retry, backoff e DLQ com RabbitMQ no Quarkus](tickets/003-rabbitmq-retry-dlq.md) — DLQ e
  ack manual são configuração; `RabbitMQRejectMetadata` dá o "não gasta retry"; mas **não
  existe retry com backoff que sobreviva a crash**, e classic dead-letta at-most-once —
  a política de falhas do mapa não se sustenta como escrita, virou o ticket 010
- [Como extrair frames a partir do serviço extracao](tickets/006-ffmpeg-extracao.md) —
  **processo externo, não JavaCV** (medido: 3,5× mais rápido, 5× menos memória);
  `eclipse-temurin:21-jre-alpine` + `apk add ffmpeg` = 467 MB; exit code classifica
  transitório vs permanente; `-xerror` é obrigatório (sem ele, MP4 truncado sai com exit 0);
  ZIP deve ser `STORED`, deflate em PNG não comprime nada
- [Política de falhas: retry durável, dead-letter e unicidade da notificação](tickets/010-politica-de-falhas.md)
  — híbrido `@Retry` no adapter + `x-delivery-limit=3` em fila quorum (Caminho C rejeitado);
  não perder a falha vale mais que não duplicar o e-mail; a guarda de unicidade é a própria
  transição de estado em `videos`, o que deixa `notificacao` sem banco e torna todo consumo
  de evento idempotente; `extracao` consome a própria DLQ. Registrado em
  [ADR 0001](../adr/0001-politica-de-falhas.md)

- [Repositório próprio e remote no GitHub](tickets/001-repositorio-github.md) — repo
  público em `vandrep/fiapx-v2`, `main` publicada, fora do índice do repo pai; o CI **não
  precisa de segredo novo** (o `GITHUB_TOKEN` autentica no GHCR), mas o default do repo é
  `permissions: read`, então o job precisa declarar `packages: write`; imagens vão para
  `ghcr.io/vandrep/fiapx-<servico>` e nascem **privadas** mesmo em repo público

## Ainda não especificado

- **Implementação do serviço `videos`** — borda HTTP, persistência, publicação de comando,
  consumo dos eventos de progresso. Vira tickets quando o contrato HTTP e o contrato de
  mensagens fecharem.
- **Implementação do serviço `extracao`** — consumo do comando, download do MinIO, ffmpeg,
  empacotamento, upload do Pacote, publicação de eventos. Depende da decisão de como
  invocar o ffmpeg.
- **Implementação do serviço `notificacao`** — consumo do evento de falha definitiva,
  template do e-mail, envio SMTP. O mais fino dos três; provavelmente um ticket só.
- **Compose completo** — Postgres, RabbitMQ, MinIO, Keycloak, MailHog, os três serviços,
  ordem de subida por health check, seed de buckets, realm e `definitions.json` do RabbitMQ.
  Só especificável depois que as dependências de cada serviço estiverem confirmadas.
- **Configuração do realm Keycloak** — clients, roles, usuários de demo, `realm-export.json`
  versionado. A pesquisa fechou os mecanismos; falta decidir se há audience mapper (sem ele,
  não configurar `token.audience`) e se o value object de dono valida formato UUID, o que
  acoplaria o domínio ao Keycloak.
- **Pipeline de CI/CD** — o workflow `verify` + build + push das três imagens para o GHCR.
  Os fatos de autenticação e permissão já estão fechados (ticket 001); falta o que só se vê
  com o esqueleto de pé: um job por módulo ou um só, cache do Maven, se o push roda em todo
  commit de `main` ou só em tag, e quem torna os packages públicos.
- **Script de smoke ponta-a-ponta** — o roteiro executável que também vira a demo.
- **Documentação de arquitetura** — formato (C4? diagrama de sequência?), onde vive, o que
  a banca precisa ver.
- **Roteiro do vídeo de até 10 minutos** — o que mostrar, em que ordem, o que não mostrar.

## Fora de escopo

<!-- ruled beyond the destination; nunca gradua -->

- **Prometheus + Grafana com dashboards** — o enunciado lista monitoramento como stack
  *recomendada*, não como requisito técnico obrigatório. Com 5,5 semanas solo, é o
  primeiro candidato a canibalizar o tempo do CI/CD. Health checks continuam dentro.
- **Manifests Kubernetes** — o enunciado aceita "Docker Compose **ou** Kubernetes";
  Compose garante a demo.
- **Interface web** — o projeto original tinha HTML embutido; a demo será por Swagger UI e
  `curl`.
- **Serviço de autenticação próprio** — Keycloak cobre isso sem código de senha.
- **E2E automatizado no CI** — Compose inteiro num runner do GitHub Actions (ffmpeg + MinIO
  + Keycloak + RabbitMQ) é fonte de flakiness que não acrescenta nota; o script de smoke
  entrega a mesma verificação.
- **Módulo Maven `shared`** — contrato de evento duplicado é mais honesto que acoplamento
  por jar; extrair depois se doer.
- **Deploy em ambiente hospedado** — provisionar ambiente consome dias que o código precisa.
