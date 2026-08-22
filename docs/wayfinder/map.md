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
| Package base | `br.com.fiapx`, com **um** modulo de negocio homonimo do servico (`br.com.fiapx.videos.core`); artefatos `fiapx`, `fiapx-videos`, `fiapx-extracao`, `fiapx-notificacao` |
| Mensageria | RabbitMQ (SmallRye Reactive Messaging) |
| Armazenamento de arquivos | MinIO (S3-compatível); serviços trocam chaves de objeto |
| Banco | Um Postgres, um database por serviço que precise — na prática **só `videos`** |
| Dono do status | `videos` é o dono; `extracao` é worker sem estado que publica eventos |
| Estados do Vídeo | `RECEBIDO` → `PROCESSANDO` → `CONCLUIDO` \| `FALHOU` |
| Falhas | Fila quorum, `x-delivery-limit=3` (conta **entregas**, crash incluído), `failure-strategy=requeue`, `@Retry` com backoff de segundos nos adapters de I/O. `extracao` consome a própria DLQ; DLQs de `videos` e `notificacao` são terminais. Unicidade da notificação na transição de estado em `videos`. E-mail é *pelo menos uma vez*. Ver [ADR 0001](../adr/0001-politica-de-falhas.md) e, para as janelas não-atômicas entre gravar e publicar, [ADR 0003](../adr/0003-reconciliacao-por-varredura.md) |
| RabbitMQ | `rabbitmq:4.3.5-management-alpine` fixado nos Dev Services e no Compose; policy `dead-letter-strategy=at-least-once` só no Compose (é policy de broker, não queue argument) |
| Autenticação | Keycloak, bearer-only via `quarkus-oidc`; dono do vídeo vem do `sub` do token, nunca do request |
| Notificação | SMTP com MailHog no Compose |
| Health checks | Sim (`quarkus-smallrye-health`), para `depends_on: service_healthy` |
| CI/CD | GitHub Actions: `verify` + build das imagens + push para o GHCR, tag do commit **e `latest`**, `amd64`+`arm64` (ticket 013). `main` protegida por ruleset: PR obrigatório, zero aprovações |
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

- [Contrato de mensagens entre videos, extracao e notificacao](tickets/007-contrato-mensagens.md)
  — cinco mensagens (comando no imperativo, evento no particípio); `extracao` e `notificacao`
  **nunca se falam**, toda falha passa pelo `videos`, que é onde mora a unicidade do e-mail.
  Sem envelope: o tipo vive na routing key, logo uma fila por tipo. O `extracao` recebe as
  chaves do MinIO prontas e não conhece a convenção. Motivo da falha é código estável, o texto
  humano é do `notificacao`. Prefetch explícito e obrigatório (`extracao`=1). Consumidor de
  mensagem é análogo a `Resource` — o template não cobria mensageria. Contrato em
  [`docs/contratos/mensagens.md`](../contratos/mensagens.md)

- [Esqueleto Maven multi-módulo a partir do template](tickets/002-esqueleto-multi-modulo.md)
  — parent agregador `packaging pom` + três módulos; `init-project.sh` roda uma vez por
  serviço **sem edição**, com `--package br.com.fiapx --modules <servico>`; o
  `ArchitectureConstraintsTest` é **reapontado, não reescrito** — uma cópia por módulo,
  com `MODULO_DO_SERVICO` e as guardas de "nenhum resource/adapter" relaxadas, porque por
  serviço elas viram falhas falsas; Dockerfile single-stage sobre o `quarkus-app` já
  empacotado, uma compilação Maven para as três imagens

- [Repositório próprio e remote no GitHub](tickets/001-repositorio-github.md) — repo
  público em `vandrep/fiapx-v2`, `main` publicada, fora do índice do repo pai; o CI **não
  precisa de segredo novo** (o `GITHUB_TOKEN` autentica no GHCR), mas o default do repo é
  `permissions: read`, então o job precisa declarar `packages: write`; imagens vão para
  `ghcr.io/vandrep/fiapx-<servico>` e nascem **privadas** mesmo em repo público

- [Contrato HTTP do serviço videos](tickets/008-contrato-http-videos.md) — quatro endpoints
  em português (`/videos`, `pacote` como sub-recurso), `202 Accepted` no envio, **uma**
  representação de Vídeo para as três respostas; download por **stream, não presigned URL**
  (presigned é *bearer token* e o host entra na assinatura); `404` para Vídeo alheio, `409`
  para Pacote indisponível; `problem+json` — com o `413` cortado pelo Vert.x fora dele.
  `motivo` expõe o **código**, nunca a frase: uma frase aqui duplicaria a tradução que é do
  `notificacao`. Swagger UI é a demo, com Authorize por fluxo `password`. Contrato em
  [`docs/contratos/http-videos.md`](../contratos/http-videos.md)

- [Modelo de domínio e script de banco do serviço videos](tickets/009-modelo-dominio-videos.md)
  — **uma tabela**, `video`: a Extração não é entidade porque o `videos` não vê tentativas,
  só entregas. A máquina de estados fica em **dois lugares com papéis distintos** — a
  entidade responde "esta transição é legal?", o `UPDATE` condicional responde "fui eu quem
  mudou a linha?" ([ADR 0002](../adr/0002-maquina-de-estados-em-duas-camadas.md)); transição
  ilegal é retorno, não exceção. A guarda de propriedade é
  **estrutural**: não existe `buscarPorId` sem dono na interface do gateway. **Sem Flyway**
  (é JDBC, custaria um datasource Agroal só para migrar): o entregável é
  [`docker/postgres/init.sql`](../../docker/postgres/init.sql), mantido honesto pelo
  `validate` em `%prod`. O `core` ganha `MotivoFalha.DESCONHECIDO`, que ninguém publica, para
  o tolerant reader não derrubar mensagem. O `Video` guarda a chave do MinIO como string
  opaca — quem a **constrói** é o `ArquivoGateway`, então a decisão do 011 pousa no adapter

- [Limites operacionais: tamanho, duração, formatos e retenção](tickets/011-limites-operacionais.md)
  — upload em **200 MB**, porque bytes não limitam frames; a guarda fina é um teto de
  **20 minutos** cobrado no `extracao`, onde o `ffprobe` já roda, e não na borda — pôr
  `ffprobe` no `videos` seria instalar ffmpeg num serviço que não conhece codecs. Daí o
  código novo `DURACAO_EXCEDIDA` (aditivo, o `DESCONHECIDO` existe para isso) e o preço
  aceito de o usuário só saber depois do `202`. A borda valida extensão e content-type de
  forma **declarativa, não probatória**: a prova é do `extracao`, via exit code. Dois
  buckets (`videos`, `pacotes`), chave **sem dono** — a autoridade sobre propriedade é o
  `dono_sub` no Postgres. Retenção é regra de ciclo de vida do MinIO (7 dias), **zero
  código**: o original **não** é apagado após sucesso, ao contrário do `main.go`. Volumes
  nomeados para o `uploads-directory` e para o scratch do `extracao`, que orça **4 GB** e
  limpa em duas camadas, porque ali o worker morre no meio por desenho

- [Transactional outbox no videos, ou conviver com o Vídeo órfão](tickets/018-outbox-transacional.md)
  — **nem uma coisa nem outra: a tabela `video` é o outbox**. Duas colunas marcadoras
  (`comando_publicado_em`, `falha_publicada_em`) mais um `@Scheduled` de reconciliação fecham
  as duas janelas não-atômicas sem tabela nova, sem payload serializado e sem reescrever o
  dispatcher. O outbox canônico compraria *exatamente uma vez*, que o ADR 0001 já recusou como
  regime; "documentar e seguir" deixou de servir porque Vídeo eternamente em `RECEBIDO` é, para
  o usuário, a requisição perdida que o enunciado proíbe. E a varredura ingênua por idade —
  o meio-termo óbvio — é **errada** no pico: backlog de fila republicaria comandos já
  publicados. A marca é o que separa "publicado e esperando" de "nunca publicado". Registrado
  em [ADR 0003](../adr/0003-reconciliacao-por-varredura.md); o código pousa no ticket 017

- [AGENTS.md da raiz para o layout multi-módulo](tickets/012-agents-md-raiz.md) — **um
  arquivo, 108 linhas contra as ~400 do template**, porque ele **não reescreve as regras de
  camada**: aponta para o `ArchitectureConstraintsTest`, que está dentro do repo e é
  executável. Duplicar seria cache de um lookup barato; apontar para o template seria link
  morto para quem clona. Ponteiros são "quando você for X, leia Y", não lista de arquivos.
  O ffmpeg **não virou ADR** — a pesquisa do 006 já o registra, faltava o ponteiro. E a
  regra "editar uma cópia é editar as três" deixou de ser prosa e virou build: derivar
  `MODULO_DO_SERVICO` do diretório do módulo torna as três cópias **byte a byte idênticas**,
  e um `cmp` na fase `validate` do **agregador** (não dos módulos, que não devem enxergar o
  vizinho) reprova a divergência em segundos. Baixar o arquivo em tempo de build foi
  recusado: build não-hermético valida o servidor, não o working tree. `CLAUDE.md` de uma
  linha aponta para o `AGENTS.md`

- [Pipeline de CI/CD: verify e push das três imagens para o GHCR](tickets/013-pipeline-ci-cd.md)
  — **um job só**, `./mvnw verify` na raiz: a matriz por módulo não cai por preço (repo
  público tem runner de 4 vCPU e minutos gratuitos), cai porque precisaria de um quarto job
  na raiz para a guarda do ticket 012 e porque a velocidade que ela compra ninguém está
  gastando — medido: `verify` em 1m14s local e 1m30s no runner, run inteiro em 2m40s. Imagens saem
  do mesmo runner, sem artifact, depois dos testes passarem. **Multi-arch `amd64`+`arm64`** é
  a decisão de maior valor: a avaliação é na máquina do avaliador, que pode ser Apple
  Silicon, e `ffmpeg` emulado inviabiliza o `extracao` — e o custo é quase zero porque os
  Dockerfiles só copiam um `quarkus-app` que é Java puro. `latest` além do SHA, porque é o
  que torna o Compose demonstrável; `concurrency` **assimétrico** (`main` nunca cancela, ou
  o `latest` fica para trás em silêncio). Fica proibido escrever `*IT.java`: `skipITs` é
  `true`, teste integrado aqui é `@QuarkusTest` no surefire. E **corrige o ticket 001**: as imagens
  **não** nascem privadas — o package criado pelo `GITHUB_TOKEN` num repo público herda a
  visibilidade dele, verificado por pull anônimo no GHCR. `main`
  protegida por ruleset com PR obrigatório e **zero aprovações** (exigir uma travaria o repo:
  ninguém aprova o próprio PR)

- [O que a API responde quando o Pacote já expirou](tickets/019-pacote-expirado.md) — o Pacote
  expira em 7 dias e a API **não finge que sabe disso**: `410 Gone` no download, e nada mais.
  O `409` fica sendo *ainda não*, o `410` é *não mais* — a diferença é operacional, porque
  `409` convida a repetir a requisição e `410` diz para parar. Expiração **não** entra no enum
  `motivo` (aquilo é falha de Extração; expirar não é falhar). Recusado dissolver o ticket
  tirando a expiração do bucket `pacotes`: o objeto some por motivos que a policy não controla
  — volume do MinIO recriado sem o do Postgres —, e sem expiração o ramo `NoSuchKey` só
  deixaria de ser exercitado. Recusado também o campo calculado `pacoteDisponivelAte`, que era
  conservador por construção (o MinIO apaga sempre *depois* do prazo, nunca antes) mas poria na
  borda um número que o `videos` não controla. Descoberta preguiçosa, e o `GET` **não escreve**:
  a tabela `video` é o registro do que aconteceu, não espelho do bucket. Sem ADR — a escolha é
  reversível e o porquê cabe no contrato HTTP

- [Implementação do serviço videos: borda HTTP e persistência](tickets/016-implementacao-videos-borda.md)
  — 66 testes verdes, **34 sem Docker**: o `core` inteiro roda com dublês em memória. Três
  achados que a especificação não tinha como prever. **O download não pode devolver `Uni`** —
  o handler de streaming do RESTEasy olha o retorno *direto* do método, e foi medido que
  `Multi` em `Response` pendura a conexão e em `Uni` sai como `toString()` do objeto (60
  bytes); a regra do `ArchitectureConstraintsTest` cedeu pela **terceira** vez, agora por fato
  medido e não por topologia. Daí a sessão do Hibernate saiu do Resource para o adapter
  (`@WithSession` exige `Uni`), o que por acaso é melhor: ela fecha antes do streaming em vez
  de segurar conexão por 1,5 GB. E **o future do `S3AsyncClient` completa na event loop do
  SDK**, o que fazia o `INSERT` seguinte morrer com `No current Vertx context found` — a ponte
  ficou no adapter, que é onde a thread estranha aparece. Resolvida também a contradição do
  ticket 009 sobre quem gera o id: `Video.novo` gera identidade, `armazenadoEm` fecha a criação
  com a chave que o gateway devolveu

## Ainda não especificado

- **Compose completo** — Postgres, RabbitMQ, MinIO, Keycloak, MailHog, os três serviços,
  ordem de subida por health check, seed de buckets, realm e `definitions.json` do RabbitMQ.
  Só especificável depois que as dependências de cada serviço estiverem confirmadas. Requisitos
  duros já chegaram. Do ticket 009: `POSTGRES_DB=fiapx_videos` (é o que cria o único
  database, antes dos scripts de init) e `docker/postgres/init.sql` montado em
  `docker-entrypoint-initdb.d`. Do ticket 011: seed do MinIO cria **dois** buckets
  (`videos` e `pacotes`) com regra de ciclo de vida de 7 dias em ambos; volume nomeado
  `fiapx-uploads` em `/var/fiapx/uploads` no `videos`; volume nomeado
  `fiapx-extracao-scratch` em `/var/fiapx/extracao` no `extracao`, com folga de **4 GB**.
  Do ticket 013: os serviços entram como `image: ghcr.io/vandrep/fiapx-<servico>:latest`,
  **sem chave `build:`** — num clone limpo `docker compose build` falha, porque `target/`
  está no `.gitignore` e os Dockerfiles são single-stage sobre um `quarkus-app` pronto.
  Do ticket 016: o `videos` precisa de `POSTGRES_DB`, MinIO, RabbitMQ e Keycloak de pé, e sobe
  com `%prod` — onde `schema-management.strategy=validate` derruba o boot se o `init.sql`
  divergir das entidades. O Keycloak importa o mesmo `realm-export.json` que os testes usam.
- **Configuração do realm Keycloak** — o arquivo **já existe**:
  [`docker/keycloak/realm-export.json`](../../docker/keycloak/realm-export.json), criado pelo
  ticket 016 porque sem ele nenhum teste de borda existiria — o realm padrão do Dev Services
  não emite o claim `email`. Ele é mínimo e só carrega o que já estava decidido: claim `email`
  (ticket 007), *direct access grants* (ticket 008), a role `usuario` e dois usuários de
  demonstração (`demo`, `outro`). **Continua aberto**: se há audience mapper (sem ele, não
  configurar `token.audience`), e o elenco final de clients e usuários que a banca vê. O
  ticket 009 já retirou daqui a pergunta do formato do `sub`: o value object `Dono` **não**
  valida UUID, então o realm pode emitir o que quiser.
- **`README.md`** — o repositório não tem um. Entregável de banca: o `docker compose up` da
  demo, o procedimento único de tornar os packages do GHCR públicos (ticket 013) e o mapa
  de leitura do repo. Só especificável depois do Compose.
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
