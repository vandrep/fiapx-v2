<!-- label: wayfinder:map -->
# Mapa: FIAP X — processamento de vídeos em Quarkus

## Destino

Entregar o Hackathon FIAP X até **29/09/2026**: o processador de vídeos do projeto base
(Go, monolito síncrono) reescrito como **três serviços Quarkus em Clean Architecture num
único repositório**, rodando em Docker Compose, com upload autenticado, processamento
assíncrono resiliente, listagem de status por usuário, download do ZIP e notificação de
erro — acompanhado da documentação de arquitetura, do script de banco, do repositório no
GitHub com CI/CD e do roteiro do vídeo de até 10 minutos.

**Destino redesenhado em 06/09/2026**: soma-se ao acima uma **camada de observabilidade** —
log, métrica e trace coletados e correlacionáveis por `idVideo`, com alertas sobre as filas.
O monitoramento estava em *Fora de escopo* desde a cartografia, recusado porque canibalizaria
o tempo do CI/CD, que é requisito. A premissa expirou: o CI/CD está entregue (ticket 013), a
fronteira ficou vazia e restam 23 dias. Pela regra do wayfinder, trabalho fora de escopo não
gradua — ele só volta se o destino for redesenhado, e então como esforço novo. É o que esta
linha faz. O que **não** entra continua fora: painel curado e canal de notificação de alerta.

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
| Escalabilidade | Medida ([026](tickets/026-linearidade-horizontal.md)): `extracao` linear até **6 réplicas** nesta máquina (eficiência 0,88, critério 0,80), 15,6 Vídeo/min; `ffmpeg` é 98,2% do tempo de serviço, e desde o [027](tickets/027-melhorias-medidas.md) roda com `-threads` derivado da cota do cgroup (−31,6%). Borda medida com réplicas atrás de proxy ([028](tickets/028-escala-da-borda.md), máquina diferente — 6 vCPU): mediana do `202` cai 5,5× (N=1→N=3); matar uma réplica de N=3 custa 39/400 recusados (9,75%) contra 361/400 (90,25%) com réplica única — não zero, por design do nginx contra `POST` não-idempotente |
| Conservação | Medida e reprovada no [025](tickets/025-carga-conservacao.md), corrigida e remedida no [027](tickets/027-melhorias-medidas.md): **0 presos** em 400 sob pico e em 133 com a borda derrubada. Terminal aceita `RECEBIDO` ou `PROCESSANDO` ([ADR 0002](../adr/0002-maquina-de-estados-em-duas-camadas.md)); `publish-confirms=true` faz a marca do [ADR 0003](../adr/0003-reconciliacao-por-varredura.md) parar de mentir |
| Testes | **144 (103 sem Docker)**. Por serviço e isolado (unitário do `core`, Cucumber pela borda HTTP, `ArchitectureConstraintsTest`); fluxo ponta-a-ponta por script de smoke versionado, não automatizado no CI |

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

- [Implementação do serviço videos: mensageria e máquina de estados](tickets/017-implementacao-videos-mensageria.md)
  — 82 testes verdes, **63 sem Docker**: as três transições, o envio e a varredura de
  reconciliação rodam com dublês em memória. Achado real: `EstadoVideo.transitaPara` lançava
  exceção também para terminal-para-**si mesmo** (`FALHOU.transitaPara(FALHOU)`), não só para
  o cruzamento entre os dois terminais — a segunda e a terceira entrega do mesmo
  `ExtracaoFalhou`, cenário central da política de falhas, derrubariam a mensagem em vez de
  dar ack. A varredura de reconciliação roda em **sequência**, não em paralelo: duas queries
  concorrentes na mesma sessão reativa do Hibernate corrompem sua pilha interna (medido em
  `quarkus dev`), o que ADR 0003 não previa ao tolerar duas réplicas varrendo ao mesmo tempo.
  `auto-bind-dlq=true` sozinho não declara a dead-letter exchange — precisa de
  `dlx.declare=true` também. Topologia (3 filas quorum, `x-delivery-limit=3`, DLQ
  compartilhada) verificada de ponta a ponta contra RabbitMQ real via management API, não só
  assumida a partir da config

- [Implementação do serviço extracao](tickets/015-implementacao-extracao.md) — 25 testes
  verdes, **17 sem Docker**; o `CucumberTest` roda **ffmpeg de verdade** contra um vídeo real
  de 3s checado no repo, sem dublê nenhum no pipeline de extração. Três achados que a
  especificação não tinha como prever: a sintaxe `-loglevel +level+repeat:error` da pesquisa
  006 não compila no ffmpeg 7.1.5 (o certo é `level+repeat+error`, sem `+` inicial nem `:`);
  `@Retry` do SmallRye só intercepta `CompletionStage` exato, nunca `CompletableFuture`
  (subtipo não conta), e não pode ser chamado do próprio bean — o retry mora num bean à
  parte, `ArquivoMinioClient`; e `@Blocking("pool nomeado")` da pesquisa 006 não existe nesta
  versão do SmallRye Reactive Messaging, só o marcador sem parâmetro. Achado operacional: o
  `ubuntu-latest` do GitHub Actions não traz ffmpeg — `.github/workflows/ci.yml` ganhou um
  passo de instalação antes do `verify`. Topologia (fila quorum, `x-delivery-limit=3`, DLX,
  DLQ com uma única binding apesar de dois canais declararem a mesma fila) verificada de
  ponta a ponta contra RabbitMQ real via management API em `quarkus dev`, não só assumida a
  partir da config. Regra nova no `ArchitectureConstraintsTest`: `ProcessBuilder` só em
  `framework`. Fora do automatizado: o caminho `TENTATIVAS_ESGOTADAS` de ponta a ponta contra
  um broker de verdade (exigiria derrubar o consumidor no meio de três tentativas)

- [Implementação do serviço notificacao](tickets/014-implementacao-notificacao.md) — 21 testes
  verdes, **14 sem Docker**; sem HTTP e sem banco, o `NotificacaoController` é a fronteira que o
  `CucumberTest` exercita, e o e-mail "enviado" é verificado por `io.quarkus.mailer.MockMailbox`
  (mock automático fora de `%prod`), não por um Dev Service de MailHog. Achado real: `DESCONHECIDO`
  chega aqui como valor **legítimo**, não hipotético — o `videos` (ticket 017) publica
  `motivo.name()` já passado pelo seu próprio tolerant reader, então um `extracao` mais novo
  vira a string `"DESCONHECIDO"` antes mesmo de chegar. A tradução do código para frase mora
  inteira no `core` (`MotivoFalha.paraFrase()`, `NotificacaoDeFalha`); `donoSub` do contrato não
  entra no e-mail nem no use case, só em log de suporte no consumidor. Decisão nova: o **texto
  do e-mail usa acentos**, ao contrário de comentários e nomes de código no resto do
  repositório — é prosa para o usuário final, não para quem lê o fonte. Achado de API:
  `MockMailbox.getMessagesSentTo` está deprecated, `getMailMessagesSentTo` devolve o tipo
  errado (`MailMessage` do Vert.x); o certo é `getMailsSentTo`. Regra nova no
  `ArchitectureConstraintsTest` (`@Incoming`/`@Outgoing` só em `framework`), aplicada às três
  cópias — adicionada de forma independente em paralelo ao ticket 017, que chegou à mesma regra
  pelo lado do `videos`. Topologia (fila quorum, `x-delivery-limit=3`, DLX, DLQ terminal
  classic, prefetch 10) verificada de ponta a ponta contra RabbitMQ real via management API

- [Compose completo: orquestração dos cinco serviços](tickets/020-compose-completo.md) —
  `docker-compose.yml` na raiz + seed do RabbitMQ (usuário `fiapx`, `guest` não autentica
  entre containers) e do MinIO (buckets + lifecycle via `minio/mc` one-shot); Keycloak com
  `KC_HOSTNAME` fixo para o issuer sobreviver a um cliente externo. Verificado com os cinco
  serviços de verdade, não só `docker compose config`: upload → ffmpeg real → `CONCLUIDO` →
  download do Pacote, e o caminho de falha até o e-mail no MailHog. Achado central: nenhum
  dos seis consumidores `@Incoming` sobrevivia a uma mensagem publicada de verdade — o
  conector RabbitMQ decodifica JSON em `JsonObject`, nunca no record do canal, e faltava um
  `MessageConverter` (`io.smallrye.reactive.messaging.MessageConverter`, não o da spec
  MicroProfile) em cada serviço. Dois gaps de config também só apareciam em runtime real:
  `videos` sem nenhuma credencial de Postgres (era Hibernate Reactive, a chave é
  `quarkus.datasource.reactive.url`, não `jdbc.url`) e `videos`/`extracao` com
  `credentials.type=static` do MinIO sem as chaves de credencial declaradas

- [README.md do repositório](tickets/021-readme.md) — 148 linhas, e **todo comando publicado
  nele foi executado contra o Compose de verdade** antes de entrar no arquivo. Dois achados só
  apareceram por isso: `curl -F "arquivo=@video.mp4"` responde **`415`** (o `curl` manda
  `application/octet-stream`, a borda exige `video/*`) — o primeiro comando que o avaliador
  copiasse falharia parecendo bug; e a premissa do próprio ticket estava errada — **não existe
  procedimento de tornar os packages do GHCR públicos**, os três já são anonimamente puxáveis
  (verificado por manifest anônimo, `200` nos três, `amd64`+`arm64`), exatamente como o ticket 013
  já havia corrigido do 001. A seção foi cortada em vez de escrita. O README **não repete**
  contrato, ADR nem regra de camada — aponta, na mesma disciplina do `AGENTS.md`; documenta o
  assíncrono como fluxo (`202` → polling → `409` antes da hora), que é o que confunde quem chega
  esperando resposta síncrona; e dá receita reproduzível para o caminho de falha, sem a qual a
  notificação de erro que o enunciado pede não seria demonstrável

- [Script de smoke ponta-a-ponta](tickets/022-script-smoke.md) — 9 passos, e a verificação e o
  roteiro da demo na **mesma peça**: um smoke mudo verificaria igual, mas ninguém o projetaria
  numa apresentação — e como este fluxo não vai para o CI, ele só prova algo se alguém rodar.
  Verificado do zero absoluto (`down -v` antes) em **1m08s**. Três achados de execução:
  `docker compose up --wait` **não serve** (o `minio-seed` é one-shot e sai com 0, o que o
  `--wait` lê como serviço morto); contar e-mails no MailHog daria **falso verde**, porque a
  caixa sobrevive entre rodadas — a asserção procura o `idVideo` no corpo, e precisa remover as
  quebras leves de quoted-printable antes, senão o UUID vem partido ao meio pelos acentos do
  ticket 014; e `set -euo pipefail` matava o script no `exit 7` do `curl` **antes** da linha
  que diz o que era esperado. Não encontrou defeito nenhum, e não era para encontrar: o ticket
  020 já percorrera o fluxo à mão — o que ele acrescenta é a repetição. `AGENTS.md` ganhou o
  ponteiro que justifica o script existir: **`./mvnw verify` não prova que os três serviços
  conversam**

- [Documentacao de arquitetura](tickets/023-documentacao-arquitetura.md) —
  [`docs/arquitetura.md`](../arquitetura.md), 341 linhas e cinco diagramas Mermaid, escrito
  **para a banca e não para o próximo dev**: os documentos existentes são todos locais e
  pressupõem contexto, e nenhum responde *por que três serviços*, *como escala* ou *como isto
  atende o enunciado*. O C3 de **um** serviço é o que justifica o C4 inteiro — Clean
  Architecture é invisível em C2, onde cada serviço é caixa opaca, e é justamente a resposta ao
  "sem nenhuma das boas práticas" com que o desafio abre. O ASCII do README **fica**: é a única
  duplicação aceita. Achado de renderização: o diagrama de containers com `subgraph` compila e
  não comunica — a separação espacial forçada cruza quase todas as arestas —, resolvido sem
  subgraphs e com `classDef`, verificado renderizando de verdade com `mermaid-cli`. E três
  afirmações minhas caíram na conferência contra o código: o `UPDATE` da unicidade casa o
  **predecessor** (`estado = 'PROCESSANDO'`), não `estado <> 'FALHOU'`; a DLQ é
  `extracao.extrair.dlq`; a chave é `schema-management.strategy`, não `database.generation`. A
  contagem de testes do próprio mapa estava velha — o real, medido no CI verde, é **130
  (96 sem Docker)**, não os 128 que a soma dos tickets dava

- [Roteiro do vídeo de até 10 minutos](tickets/024-roteiro-video.md) —
  [`docs/roteiro-video.md`](../roteiro-video.md), narração **integral** que fecha em **9:19**
  medidos. O formato veio do fluxo de produção: filmar, editar, dublar por cima desacopla a
  narração do tempo de execução, então o vídeo é acelerável e o **áudio** é o único com teto —
  logo a unidade de orçamento é a palavra, não o segundo, e quem dubla lê em vez de improvisar
  sobre tópicos. Isso pagou na hora: a primeira versão media 9:42 e o corte saiu no editor de
  texto, não numa regravação. Medindo tomada a tomada, o estouro não estava distribuído —
  estava quase todo no diagrama de caminho de falha, que é justamente onde moram as duas
  garantias que nenhuma demonstração mostra, então ela foi enxugada e **ganhou** tempo, tirado
  das tomadas que só descrevem o que já está na tela. Ordem invertida em relação ao enunciado
  (**funcionando antes de arquitetura**: quem vê o `202` entende por que os diagramas são
  assim), e "Documentação" **não é bloco** — a documentação é o que está na tela durante a
  arquitetura. Dentro da demo, **acelerar com marca `4×`, nunca cortar**: acelerar preserva a
  continuidade, e um corte dentro de uma verificação levanta a dúvida que a demonstração existe
  para fechar. O fechamento **admite duas limitações em voz alta**, que já estavam no
  `arquitetura.md`. Toda afirmação numérica foi conferida contra o código, e a tomada da árvore
  de módulos filma `git ls-files` porque o `init-project.sh` deixou `com/example/` vazios que o
  git não rastreia mas um `tree` local mostraria

- [Harness de carga e prova de conservação sob pico](tickets/025-carga-conservacao.md) —
  harness em `scripts/carga/` (fixtures por `ffmpeg`, injetor k6 em container, oráculo em SQL +
  amostra pela API) e overlay `docker-compose.carga.yml`. O veredito é o que o ticket existia
  para arriscar: **a afirmação não se sustenta**. A borda conserva — 400 envios simultâneos de
  1 MB deram 400 `202`, zero recusas, em toda rodada com a borda viva —, mas o **evento terminal
  é descartado em silêncio quando chega fora de ordem**: `Iniciada` e `Concluida` vêm em filas
  independentes, e a `Concluida` que chega primeiro não casa o predecessor, altera zero linhas e
  recebe ack. Vídeo preso em `PROCESSANDO` para sempre — e a prova não é raciocínio, é o bucket:
  **45 de 45 presos têm o `.zip` gravado**. 11/400 sob pico com réplica reiniciada, 34/39 depois
  de o `videos` cair. Mais dois achados: a marca do ADR 0003 **mente** (`publish-confirms` é
  `false` por default, então "publiquei" completa antes do broker confirmar — 3 Vídeos em
  `RECEBIDO` com marca preenchida e comando nenhum na fila, e a varredura filtra por marca nula,
  logo nunca os reconsidera); e a varredura de órfãos no boot do `extracao` **apaga o scratch das
  réplicas vivas**, porque o volume nomeado é compartilhado e o Javadoc assume exclusividade — um
  h264 válido chegou ao usuário como `ARQUIVO_INVALIDO`. O critério 5 do harness ("zero FALHOU
  com fixture válido") nasceu disso: os quatro originais deixavam passar terminal-porém-errado.
  Nada corrigido aqui, por desenho — os três foram para o 027 com o número ao lado

- [Linearidade horizontal do extracao](tickets/026-linearidade-horizontal.md) — **a afirmação se
  sustenta**: eficiência de escala 0,99 / 0,90 / 0,88 em 2 / 4 / 6 réplicas, nunca abaixo do
  critério de 0,80 fixado antes de rodar; de 2,96 para **15,62 Vídeo/min**. Mas o veredito é a
  parte menor. O pré-registro pagou três vezes, e uma delas **mudou o resultado**: pelo cronômetro
  de parede a eficiência seria 0,95 / 0,80 / **0,71** e a afirmação reprovaria em `N=6` — por
  artefato do instrumento, porque a rampa de injeção entra no denominador penalizando quem drena
  rápido. Descontar isso depois de ver o número seria indistinguível de fabricar o resultado. A
  partição condenou a conta que a motivou: **`ffmpeg` é 98,2%** do tempo de serviço, e os ~3 s que
  o 006 previa eram de máquina **sem teto de CPU** (3,04 s no host contra 20,84 s dentro de uma
  cota de 2). E o controle sujo fechou uma das três coisas que o 025 não mostrou: a degradação
  suspeita de ser estado acumulado **não existe** (0,0499 contra 0,0498, sobre três corridas de
  lixo). O achado que mais custou: **duas das doze corridas foram corrompidas pelo host
  suspendendo no meio**, modo de falha que não perde, não falha e não reinicia nada — só estica o
  denominador —, e que passou nos cinco portões. `n1-r2` marcou metade da vazão e, tivesse entrado
  na mediana, a curva inteira apareceria com eficiência **acima de 1,0**. Daí o sexto portão,
  continuidade da série de telemetria. Dois erros meus de instrumento
  (locale do `awk`, e `docker stats` com leituras inutilizáveis) empurraram a ocupação do host
  para o `/proc/stat`: **77% dos 20 núcleos em `N=6`** — não saturou, mas a folga acabaria em
  `N=8`. Números em
  [`docs/pesquisa/carga-escalabilidade.md`](../pesquisa/carga-escalabilidade.md); o primeiro
  candidato de **vazão** do 027 (`-threads 2` recupera 32%) saiu daqui


- [Melhorias justificadas pela medição](tickets/027-melhorias-medidas.md) — as quatro condenadas
  corrigidas, cada uma remedida: presos em `PROCESSANDO` de **11/400 para 0/400** sob pico e de
  **34/39 para 0/133** com a borda derrubada; `ffmpeg` de 18,54 s para **12,68 s** (−31,6%) sob
  `cpus=2`. O defeito 1 virou emenda ao **ADR 0002** — terminal aceita `RECEBIDO` **ou**
  `PROCESSANDO`, porque `PROCESSANDO` é acompanhamento e não portão, e o desfecho descartado
  estava no bucket. O achado que mais desarma: **um teste do repo afirmava o defeito**
  (`concluirSemPassarPorProcessandoNaoMudaNada`), escrito com confiança e invertido aqui. O
  dilema do defeito 4 **se dissolveu num fato** em vez de virar decisão: `availableProcessors()`
  já lê a cota do cgroup (`Effective CPU Count: 2` enquanto `nproc` diz 20), então o número certo
  é derivado e a propriedade configurável foi recusada — número que a JVM sabe ler não deve virar
  conhecimento tribal em `.env`. E a condição de aceite dura do defeito 2 revelou a causa de a
  varredura do ADR 0003 nunca ter sido flagrada: ela era **muda**, então nenhuma medição poderia
  tê-la visto agir. Agora registra o que republica, e foi observada — mas contra um órfão
  **semeado**, porque em duas rodadas `mata-videos` a queda não produziu órfão nenhum (134 linhas,
  134 com marca): exercita o mecanismo, não a corrida que o cria, e o ADR diz isso. Duas previsões
  minhas caíram na medição: o round-trip do `publish-confirms` **não custa nada mensurável**
  (mediana do `202` 11233 ms contra 11504 ms sem), e o harness **dava falso verde** — `mata-videos`
  passou duas vezes com **0 aceitos**, porque os critérios 2 a 5 se satisfazem com 0/0. Ganhou
  portão de validade de rodada, na mesma família do sexto portão do 026. Dois buracos de
  instrumento a mais: o `IN` novo não tinha teste que o alcançasse (nem `core` nem BDD — daí
  `VideoDataSourceAdapterTest` contra Postgres de verdade), e o harness **mede a imagem que
  estiver por perto**, porque nada no repo a constrói. **144 testes (103 sem Docker)**

- [Escala da borda](tickets/028-escala-da-borda.md) — **a afirmação quase se sustenta**. N=3
  réplicas do `videos` atrás de um proxy L7 (`nginx`, novo no overlay de carga) reduzem a
  mediana de latência do `202` em **5,5×** (630→114 ms) sob 400 conexões simultâneas contra
  N=1; a vazão de dreno não muda, porque quem limita é o `extracao`, não a borda. Matar uma
  réplica de N=3 durante a rajada custou **39 recusados de 400 (9,75%)**, contra os 361/400
  (90,25%) do [025](tickets/025-carga-conservacao.md) com réplica única — 9,3× menos perda,
  mas não zero. Os 39 são todos `502`, nenhum timeout: o nginx recusa, por padrão, reencaminhar
  um `POST` (não-idempotente) para outra réplica depois que a conexão já falhou esperando
  resposta, porque a réplica morta pode já ter completado o efeito colateral antes de morrer —
  reencaminhar arriscaria duplicar o Vídeo. `non_idempotent` fecharia a lacuna às custas desse
  risco; **não foi ligado**, fica como candidato não implementado — o endpoint não tem chave de
  idempotência que absorva o retry. Achado de instrumento: esta sessão roda
  Docker-outside-of-Docker, e bind mount por caminho relativo e checagem por `localhost`
  precisaram de tratamento novo no harness (`--project-directory` com o caminho real do host,
  oráculo de amostra rodando dentro de um container na rede do Compose) — não mexeu em
  `oraculo.sh`. Máquina diferente das três medições anteriores (6 vCPU, não 20): números de
  vazão não comparáveis entre sessões, só as comparações internas valem. Números completos em
  [`docs/pesquisa/carga-escala-borda.md`](../pesquisa/carga-escala-borda.md)

- [A falha definitiva do `extracao` tem confirmação e tem fundo](tickets/029-terminal-na-dlq-do-extracao.md)
  — **a leitura do conector desmentiu a primeira redação**: não é reenfileiramento infinito,
  é perda silenciosa — `publish-confirms=false` (default) nos três canais de saída do
  `extracao` fazia uma publicação recusada pelo broker completar como sucesso, o consumidor
  da própria DLQ dar ack, e a falha definitiva sumir sem circular e sem reentrega. Ligar
  confirms sozinho criaria o loop que faltava limitar; as duas metades andaram juntas:
  `publish-confirms=true` nos três canais de saída, `failure-strategy=reject` (não mais
  `requeue`) na `extracao.extrair.dlq`, que ganhou DLX próprio (quorum, sem
  `x-delivery-limit` — um salto é o desenho) para a `extracao.extrair.estacionamento`, nova
  fila terminal sem consumidor. `ExtracaoDlqConsumer` ganhou um `WARN` com o `idVideo`. Achado
  na revisão de código da própria implementação: o `@QuarkusTest` de topologia inicial
  publicava payload inválido para forçar nack, mas a conversão de payload roda fora do `Uni`
  por mensagem que o `failure-strategy` trata — uma exceção ali pode derrubar a subscription
  do canal inteiro em vez de nackear só aquela entrega. Trocado para um `ExtrairVideo` válido
  contra um canal de saída quebrado de propósito (`@TestProfile`), o mesmo mecanismo do modo
  `mata-publicacao` novo em `scripts/carga/conservacao.sh`. Não medido nesta sessão: o
  `@QuarkusTest` novo e o `mata-publicacao` contra o Compose de verdade — o sandbox usado
  não roteia porta publicada de container (docker-outside-of-docker), então só compilação e
  os testes sem Docker rodaram; falta confirmar os dois no ambiente normal. Migração
  documentada e não testada: `extracao.extrair.dlq` clássica existente quebra o boot com 406
  `PRECONDITION_FAILED` — a fila precisa ser apagada antes do deploy. O Vídeo continua preso
  em `PROCESSANDO` sem e-mail ao Dono nesse caminho — visibilidade, não o desfecho, é o que
  este ticket compra. Achado no processo, não no código: o defeito de `publish-confirms` já
  tinha acontecido um serviço abaixo (027, no `videos`) e se repetiu aqui sem guarda nenhuma
  — motivo do [034](tickets/034-publish-confirms-sem-guarda.md), aberto na mesma revisão.

- [Deploy não gasta tentativa da Extração](tickets/030-deploy-nao-gasta-tentativa.md) — a
  pergunta central tem resposta e é **não**: o conector cancela a assinatura e fecha o canal
  de forma síncrona e incondicional em `@BeforeDestroyed(ApplicationScoped.class)`, sem
  esperar mensagem em voo, e isso dispara em `Arc.shutdown()` — **depois** da fase graciosa
  nova do Quarkus, não protegido por ela. `quarkus.shutdown.timeout` só espera
  `HttpServerRequest` (`GracefulShutdownFilter`); Reactive Messaging não tem equivalente.
  `stop_grace_period`, por maior que seja, não muda quando isso acontece — acontece em
  milissegundos após o `SIGTERM`, sempre. A premissa não se sustenta como escrita, e como o
  003 virou o 010, este vira o [035](tickets/035-drenar-extracao-antes-do-sigterm.md): falta
  um mecanismo que atrase o desligamento gracioso até a Extração terminar e dar ack, e nada
  foi configurado sem essa prova. Achado com código-fonte citado por linha em
  [`docs/pesquisa/rabbitmq-retry-dlq.md` §8](../pesquisa/rabbitmq-retry-dlq.md#8-adendo-ticket-030-o-conector-espera-a-mensagem-em-voo-terminar-no-sigterm)

- [Falha da DLQ chega ao Estacionamento](tickets/037-estacionamento-nao-recebe-falha-da-dlq.md)
  — exchange inexistente fecha o canal AMQP e deixa o confirm pendente no Vert.x 4.5.24;
  as retentativas do SmallRye nunca recebem uma falha para tratar. O publicador de
  `ExtracaoFalhou` ganhou teto total de 30 segundos: ao vencer, propaga falha e o `reject`
  da DLQ leva o comando original ao Estacionamento. Provado com RabbitMQ real e suíte da
  raiz passando (392 testes). Timeout não prova recusa: publicação tardia pode coexistir
  com o comando estacionado; duplicatas continuam tratadas pelo dono do estado.

- [Dev Services provados no devcontainer rootless](tickets/036-dev-services-no-devcontainer-rootless.md)
  — o rebuild confirmou rede do host, loopback anunciado ao Testcontainers e caminho real do
  socket entregue ao Ryuk. Um Nginx publicado pelo daemon respondeu de dentro do container, e
  `VideoDataSourceAdapterTest` passou com Ryuk, Postgres, RabbitMQ, LocalStack e Keycloak reais.
  O socket deixou de pressupor UID 1000: deriva de `XDG_RUNTIME_DIR`; o suporte documentado é
  Linux com Docker rootless. A suíte da raiz chegou ao código e revelou uma falha funcional no
  Estacionamento, separada no [037](tickets/037-estacionamento-nao-recebe-falha-da-dlq.md).

- [Nada impede um canal de saída novo de nascer sem publish-confirms](tickets/034-publish-confirms-sem-guarda.md)
  — o buraco que o 029 fechou no `extracao` e o 027 já tinha fechado no `videos` virou regra de
  build: quinta regra do `ArchitectureConstraintsTest`, a primeira que não julga código Java.
  Ela lê o `application.properties` do próprio módulo e cobra `publish-confirms=true` em todo
  canal `mp.messaging.outgoing.*` que publique em RabbitMQ, nomeando serviço, arquivo e canal
  na falha. Não precisou de cópia divergente nem de módulo compartilhado: cada cópia do teste
  roda com o CWD no seu basedir, o mesmo pressuposto de `MAIN_SOURCES`, então o arquivo idêntico
  nos três cobre os três serviços. A revisão da própria implementação alargou a regra: cobra
  também o canal **sem** `connector` declarado, porque com um conector só no classpath o Quarkus
  o liga ao RabbitMQ do mesmo jeito — cobrar só a linha `connector=` deixaria passar justamente o
  canal novo do título. Junto foram fechados separador `:`, nome de canal com ponto e
  `publish-confirms=false` num perfil sobre um `true` sem perfil. Limite declarado: canal que
  chegue por `MP_MESSAGING_OUTGOING_*` (o overlay de carga usa) passa por fora. Vale para o `notificacao`, que hoje não publica — a regra
  protege o serviço, não o canal que existe. Validação em boot foi descartada: avisa mais tarde
  e custa mais para testar. Provado nos dois sentidos: apagar o `publish-confirms` de
  `extracao-falhou` reprova com a mensagem esperada; restaurado, 11 testes verdes nos três
  módulos. Não vinha da rodada de arquitetura dos 029–033: saiu da revisão de código da
  implementação do 029, na mesma sessão.

- [Mensagens e identidades sobrevivem à recriação da stack](tickets/044-preservar-mensagens-ao-recriar-rabbitmq.md)
  — RabbitMQ ganhou volume nomeado e hostname estável; Keycloak ganhou volume para manter
  o `sub` do dono dos Vídeos. O ensaio isolado `scripts/persistencia-rabbitmq.sh` exige
  comandos confirmados e marcas no Postgres antes do `down`, preservação da topologia
  sem os serviços ligados e conclusão dos mesmos Vídeos pela API após o `up`.

- [O estado após a expiração é verificado pela borda](tickets/046-verificar-estado-apos-expiracao-pela-borda.md)
  — o cenário do Pacote expirado parou de ler a `VideoEntity` para afirmar que o Vídeo
  continua em `CONCLUIDO`: agora consulta o mesmo Vídeo pela API e baixa o Pacote de novo,
  exigindo `410` outra vez. O segundo `410` é a prova indireta de que o `GET` que descobre
  a ausência não gravou nada — estado alterado daria `409`, e a `chavePacote` apagada
  quebraria antes. A chave continua fora do `VideoViewModel`, de propósito.

- [A representação de Vídeo tem uma conversão só](tickets/047-unificar-conversao-da-representacao-de-video.md)
  — os dois presenters repetiam o mapeamento dos sete campos públicos. A conversão virou
  `RepresentacaoDeVideo`, classe sem estado na apresentação, e a listagem monta cada item por
  ela. Um teste unitário com esperas escritas à mão e dois cenários BDD — CONCLUIDO e FALHOU
  — passaram a cobrar que item de listagem e consulta individual saiam idênticos, com as sete
  chaves públicas e nenhuma de armazenamento. Achado de lambuja, ainda aberto: o `202` do
  envio publica `recebidoEm` com nanossegundos e o `GET` publica o mesmo campo truncado em
  microssegundos pelo Postgres.

- [A borda também absorve o blip do armazenamento](tickets/048-retry-no-acesso-ao-minio-pela-borda.md)
  — o ADR 0001 dizia que o `@Retry` no adapter cobre os blips de I/O contra o MinIO, e só os
  dois workers cumpriam. Implementado em vez de excetuado: atrás da borda síncrona não há fila
  quorum para reentregar, então o blip que ela não absorve já virou `500` no cliente. O acesso
  ao MinIO do `videos` ganhou o mesmo par de beans do `extracao` — `ArquivoMinioClient` com o
  `@Retry`, `ArquivoMinioAdapter` com bucket e convenção de chave. Diferença do worker: o
  `NoSuchKeyException` vira `Optional.empty()` **dentro** do método anotado, para a chave
  ausente continuar `410` imediato em vez de gastar três tentativas. Quatro cenários pela
  borda cobram os dois caminhos síncronos vezes os dois desfechos: com o blip, o envio responde
  `202` e o download entrega o Pacote inteiro; com o armazenamento sempre fora, os dois
  respondem `500`, com o corpo `problem+json` que o contrato prevê.

- [A demo processa mais de um vídeo ao mesmo tempo](tickets/049-compose-da-demo-processa-em-paralelo.md)
  — o primeiro requisito do enunciado só era exercido pelo overlay de carga: a stack do README
  subia uma réplica do `extracao` com `max-outstanding-messages=1`, um vídeo por vez. Resolvido
  por réplicas, não por mensagens em voo — o prefetch de 1 protege memória e disco de uma
  extração de até 4,4 GB de PNG, e subi-lo poria N extrações no mesmo JVM e no mesmo scratch.
  `deploy.replicas: ${FIAPX_EXTRACAO_REPLICAS:-2}` no Compose da demo — o ponto medido com
  eficiência 0,99, e a mesma variável que o overlay já usava. Fica registrado o que a réplica
  extra muda e o prefetch não protegia: o scratch é um volume só, então o pior caso de disco
  dobra, e sem teto de CPU as duas se sobre-assinam (o 0,99 foi medido com `cpus=2`).
  `scripts/concorrencia.sh` observa a concorrência pela borda pública — rajada de oito,
  amostragem da listagem a cada 100 ms, critério de pelo menos dois em `PROCESSANDO` no mesmo
  instante e por duas amostras seguidas, porque um instante isolado é indistinguível de
  reordenação entre `extracao.iniciada` e `extracao.concluida` — e foi conferido por controle
  negativo: com uma réplica só, ele reprova e a linha do tempo sai em escada. O smoke e o
  ensaio de persistência contavam saúde por linha de `docker compose ps` e esperariam até o
  timeout com duas réplicas; passaram a contar serviços distintos e containers não-saudáveis.

- [A folga contra crash vale para as duas metades da varredura](tickets/050-folga-contra-crash-nas-falhas-pendentes.md)
  — a reconciliação do ADR 0003 protegia comandos pendentes com um minuto de folga e falhas
  pendentes com nenhuma, então uma passada que caísse sobre uma publicação de `VideoFalhou` em
  voo republicava o evento e duplicava o e-mail. **Simetrizado**, e não registrado como
  decisão: a janela entre gravar e publicar é a mesma dos dois lados, e documentar a ausência
  seria inventar justificativa para um descuido. Um instante de corte por passada governa as
  duas buscas, e o da falha é comparado com `finalizado_em`. O limite fica registrado no ADR:
  `finalizado_em` é o instante do **evento**, não o da escrita, então sob backlog de fila a
  folga efetiva encurta — no pior caso ela vira a de antes, zero, e o pior caso continua sendo
  a duplicata que o ADR 0001 aceita; coluna nova pagaria migração por essa diferença. Sem
  mudança de esquema: o índice parcial da falha já era `(finalizado_em)`. O teste que
  reprovava antes é o gêmeo do que já existia para o comando, e o predicado novo, que é HQL e
  nenhum dublê alcança, ganhou teste contra Postgres de verdade.

- [O tipo fala o vocabulário do glossário, e cada método carrega regra](tickets/051-ciclo-da-extracao-no-glossario.md)
  — `CicloDaExtracao` usava em código um termo que o `CONTEXT.md` não define, contra a regra do
  `AGENTS.md`. Resolvido **pelo lado do código**: a classe virou `Extracao`, o termo que o
  glossário já define, em vez de o glossário ganhar um verbete novo. "Ciclo" não nomeava nada
  que a Extração já não nomeasse — a peça não modela ciclo de vida com estados próprios, ela
  decide o desfecho de uma Extração a partir dos sinais do `ffmpeg` e do `ffprobe` —, e um
  verbete a mais obrigaria o leitor a distinguir dois termos onde o domínio tem um. O
  `CONTEXT.md` ficou intocado de propósito. Na mesma passada, `motivoSeSondagemFalhou(int)` e
  `motivoAoValidarFluxoDeVideo(boolean)` saíram: eram ternário → `Optional`, sem limiar nem
  tolerância, e viraram duas guardas explícitas no adapter, onde a precedência entre
  `ARQUIVO_INVALIDO` e `SEM_FLUXO_DE_VIDEO` passou a ser a ordem das linhas em vez de semântica
  de `Optional` encadeado. Ficaram na entidade os três que decidem algo: a tabela de exit codes
  do ffmpeg, o teto de duração e a tolerância de 10% da contagem de frames. Classificação
  idêntica, entrada por entrada. Dos dois caminhos que o unitário removido cobria, um já era
  exercido pela borda — o cenário BDD do arquivo que não é vídeo —, e o outro,
  `SEM_FLUXO_DE_VIDEO` na sondagem de stream, teria ficado descoberto: o cenário BDD para na
  primeira sondagem, a de duração. A revisão o pegou, e ele ganhou teste próprio contra
  `ffprobe` de verdade sobre um segundo de áudio sem vídeo nenhum.

- [Os dados da conclusão viajam como um conceito só](tickets/052-nomear-o-resultado-da-extracao-no-videos.md)
  — `ResultadoExtracao` (record: `concluidaEm`, `chavePacote`, `quantidadeFrames`,
  `tamanhoPacoteBytes`) entra em `videos/core/entities` e passa a viajar inteiro pelas cinco
  assinaturas que carregavam os quatro dados soltos, do `ExtracaoEventosConsumer` até o
  `VideoDataSourceAdapter`. A desambiguação de `tamanhoBytes` acontece na própria borda de
  mensageria — é onde o consumidor monta o conceito a partir do evento —, então nenhum nome
  do caminho fica ambíguo entre o tamanho do Pacote e o do Vídeo. O contrato
  (`framework.dispatcher.ExtracaoConcluida`) não mudou. O nome escolhido foi
  `ResultadoExtracao`, não `Extracao`: o 051 reservou essa disputa para aqui, mas `videos` e
  `extracao` são serviços diferentes sem módulo compartilhado, e o vocabulário do `videos` é
  sobre o Vídeo que concluiu, não sobre a Extração em si — não havia disputa de fato.

- [A forma comum aos três use cases de evento mora em `TransicaoDeVideo`](tickets/053-unificar-a-forma-dos-use-cases-de-extracao.md)
  — classe utilitária em `core/usecases/video`, sem sufixo `UseCase.java` (mesmo padrão de
  `PublicarVideoFalhou`): busca o Video, aplica a transição da entidade, curto-circuita
  quando ela recusa ou o Video não existe, grava, e roda um efeito posterior opcional. Só
  `ProcessarExtracaoFalhouUseCase` usa o efeito posterior, para publicar `VideoFalhou`; os
  outros dois passam um no-op. A decisão de transição continua na entidade (ADR 0002); nada
  mudou no que cada evento aceita ou recusa.

- [Os nomes qualificados inline viraram import](tickets/054-nomes-qualificados-inline.md)
  — `VideosResource` (`videos`) e `EspacoDeTrabalhoAdapter` (`extracao`) escreviam tipo,
  anotação e utilitário por extenso (`java.net.URI`, `jakarta.ws.rs.DefaultValue`,
  `java.util.function.Supplier`, `java.util.concurrent.CompletableFuture`) no meio do
  código, destoando do resto dos próprios arquivos. Nenhum dos casos desambiguava tipo
  homônimo, então todos viraram import comum; nada mais mudou.

- [As quatro escolhas sem registro ganharam a frase que faltava](tickets/055-registrar-as-escolhas-fora-do-enunciado.md)
  — nenhuma mudou de comportamento, só ficou explicada onde a banca olha. A ausência de
  Prometheus/Grafana entrou no roteiro do vídeo (Bloco 4) como recusa deliberada, coerente
  com o que este mapa já registra em Fora de escopo. `.claude/skills/` e `.devcontainer/`
  ganharam um parágrafo no README (`Ferramental de agente versionado`) dizendo por que
  vivem no repositório de entrega em vez de num `.gitignore`. E o CSS que esconde
  `client_id`, `client_secret` e o seletor de client credentials no Authorize do Swagger
  ganhou uma linha no README, ao lado de onde a demo já manda clicar em Authorize — o
  comentário dentro do próprio CSS já explicava o "porquê" para quem lê código, mas
  ninguém que só abre o repositório ou assiste ao vídeo passa por `META-INF/branding/`.
  *Revertido em parte pelo [ticket 074](tickets/074-remover-o-ferramental-de-agente-versionado.md):
  a metade das skills saiu do rastreamento — ver Fora de escopo. A do `.devcontainer/` vale.*

- [O piso de observabilidade entrou, medido](tickets/058-piso-de-observabilidade.md)
  — primeiro ticket do destino redesenhado de 06/09/2026. `grafana/otel-lgtm:0.32.1` num
  container só, no Compose principal, fora do caminho de boot e sem volume. **Custa 365 MiB
  de RAM** (5.814 → 7.857 → 8.222 MiB, host ocioso → demo → demo com a stack) e +23 s no `up`;
  o custo que pesa é disco, 3,6 GB de imagem. A sobrecarga sobre o fixture de controle **não
  se distingue do ruído** (7/6/7 s contra 6/7/6 s) — mas isso mede só competição por recurso,
  porque os serviços ainda não exportam nada; o custo da instrumentação é do 059. As métricas
  de fila vêm do `rabbitmq_prometheus`, que já estava habilitado, em `/metrics/detailed` (o
  `/metrics` padrão é agregado e não tem rótulo de fila). Os três alertas avaliam, e o de
  **fila com mensagem e zero consumidores** foi validado reproduzindo o incidente de 06/09.
  O overlay de carga desliga a stack com `replicas: 0` — o que o 062 depois mostrou preservar
  menos do método dos tickets 025–028 do que se supunha, porque desligar a stack e os
  exportadores não desliga a instrumentação. Sem canal de notificação: os alertas existem,
  **a detecção não mudou**.

- [Os três sinais saem dos três serviços, costurados pelo idVideo](tickets/059-tres-sinais-nos-tres-servicos.md)
  — buscar um `idVideo` devolve **um** trace com `fiapx-videos`, `fiapx-extracao` e
  `fiapx-notificacao` dentro, e os logs dos três chegam ao Loki com `idVideo` como campo e o
  `trace_id` do mesmo trace. O contexto atravessa o RabbitMQ por header AMQP; os cinco records do
  contrato ficaram intactos. Duas coisas foram **medidas e mudaram uma decisão**: a
  auto-instrumentação encerra o span de recebimento *antes* do método `@Incoming` rodar, então
  sem um span nosso a publicação seguinte viraria raiz e o rastro se partiria em cada salto; e a
  extensão da AWS, que monta o `AwsSdkTelemetry` sozinha, **não emitiu nenhum span de S3** — eu
  havia removido os spans próprios de MinIO por causa dela e tive de devolvê-los. Uma métrica
  própria só: `fiapx.extracao.duracao`, os 98,2% do tempo de serviço que rodam fora do JVM.
  **Custo da observabilidade, que o 058 deixou por medir: ~5% no ciclo do Vídeo (0,56–0,59 s
  contra 0,53–0,56 s) e ~160 MiB somando os três serviços**, com amostragem em 100% — o
  [062](tickets/062-a-chave-que-nao-desliga-o-sdk.md) reetiquetou o que esse delta contém
  (exportar os três sinais, gravar métrica e espelhar log; **não** gravar span, que os dois
  lados pagam). O
  `smoke.sh` ganhou os passos 10 e 11 — o 10 é a única prova de correlação ponta a ponta que
  existe no repositório, e reprovou de verdade antes de a busca ser ancorada no serviço certo.
  Deixou um defeito medido em aberto, [061](tickets/061-travamento-raro-com-o-sdk-desligado.md).

- [A camada de observabilidade virou registro](tickets/060-registrar-a-camada-de-observabilidade.md)
  — fecha a cadeia 058–060, na direção inversa do 055: lá o trabalho era registrar escolha sem
  registro, aqui é desmentir quatro textos que a entrega tornou falsos. `docs/arquitetura.md`
  mudou nos três pontos, e a linha da tabela de recusados passou a recusar **painel curado e
  canal de notificação**, não monitoramento inteiro. Entraram **quatro limitações novas**, sem
  eufemismo: a detecção não mudou (alerta sem canal é a mesma propriedade do health check no
  incidente de 06/09), a retenção morre no `down`, a imagem da stack é de demonstração, e **a
  configuração medida não é a entregue** — o overlay de carga desliga a observabilidade para
  preservar o método dos tickets 025–028, então os números de escala descrevem um sistema que a
  demo não é. **ADR 0004** responde as três perguntas que não tinham onde ser respondidas:
  `core` sem span (com o teste endurecido, porque a lista nominal mentia por omissão), `idVideo`
  × `trace_id`, e o overlay que desliga. `docs/contratos/mensagens.md` ganhou § Headers —
  `traceparent` ao lado do `x-death`, corpos intactos —, e o `AGENTS.md` a regra de nomes: o que
  o OTel emite fica como o OTel emite, o que é nosso usa o `CONTEXT.md`. O **`CONTEXT.md` não
  mudou, e é decisão**: trace, span e travessia são vocabulário de infraestrutura, e glossário é
  glossário. No roteiro, as 53 palavras que narravam a recusa viraram 16 de afirmação e o Bloco 2
  ganhou o passo de trace dentro do take que já existia — 1.404 palavras, **9:41**, o mesmo teto
  de antes. O único número novo é medido: três corridas de `smoke.sh` completo com a stack quente, 45/45/46 s.

- [Uma Extração trava, raramente, com o SDK desligado](tickets/061-travamento-raro-com-o-sdk-desligado.md)
  — a causa **não era o SDK**, e o título do ticket é o nome de uma correlação que a medição
  desfez. Reproduzido em `scripts/carga/travamento.sh` (novo: repete ciclos com teto e, no
  primeiro que estoura, coleta filas, *scratch* e thread dump enquanto a réplica ainda está
  presa) e localizado por sondas: a Extração para **entre** o adapter do MinIO e a primeira
  linha do método guardado, sem thread, sem socket, sem retentativa e sem log. É a tolerância a
  falhas por interceptor: numa operação verdadeiramente assíncrona o SmallRye monta
  `RememberEventLoop -> ThreadOffload` e **reagenda a chamada no contexto Vert.x do próprio
  consumidor**, que só é liberado quando aquela chamada terminar — o reagendamento entra atrás
  de quem espera por ele. A/B no mesmo host: **4 travamentos em ~60 ciclos com `@Retry` +
  `@AsynchronousNonBlocking`, 0 em 90 sem eles**. A retentativa do ADR 0001 passou a ser
  `onFailure().retry()` do Mutiny nos três serviços — mesma contagem, mesma espera, sem
  reagendar nada —, a extensão saiu dos três `pom.xml` e uma regra nova do
  `ArchitectureConstraintsTest` barra o interceptor voltar. **Achado colateral, e o mais caro**:
  `QUARKUS_OTEL_SDK_DISABLED=true` não desliga a instrumentação, só a exportação — o span
  continua gravando. O guarda por `isRecording()` do `Rastro` nunca dispara: as duas pernas do
  A/B do 059 eram, no código, a mesma perna, e a variável em torno da qual este ticket inteiro
  foi escrito não existia. Segue no 062.

- [Chaves órfãs de Fault Tolerance não sobrevivem à regra do 061](tickets/064-chaves-orfas-de-fault-tolerance.md)
  — as duas configurações de `@Retry` que restavam no perfil de teste do `videos` saíram; a
  repetição é do `onFailure().retry()` do Mutiny e seus valores vêm do código. A regra
  arquitetural agora alcança também o `application.properties` dos três serviços e barra
  chaves do MicroProfile e o namespace `quarkus.fault-tolerance`.

- [O adapter de ffmpeg não esconde diferença atrás de Middle Man nem bandeira](tickets/066-middle-man-e-bandeira-no-adapter-de-ffmpeg.md)
  — `falhaPermanente(...)` saiu e todos os pontos constroem a exceção diretamente. Os dois
  wrappers de execução e `capturarStdout` também saíram: um único `executar(...)` redireciona,
  aguarda e lê stdout e stderr. A classificação de falha permanente ou transitória pelo exit
  code ficou intacta.

- [O overlay de carga mede um sistema instrumentado, sem coletor](tickets/062-a-chave-que-nao-desliga-o-sdk.md)
  — a decisão que o 061 deixou aberta. Ele fica **como está** e passa a declarar o que mede, das
  três saídas possíveis a única que existe: nenhuma chave que pararia o span alcança um overlay
  de Compose. Verificado, não suposto — `quarkus.otel.enabled=false` é fixado no build e nem
  compila aqui (somem os beans `Tracer` e `Meter`); `otel.sdk.disabled` pelo autoconfigure é a
  mesma configuração com outro nome; o sampler `always_off` funciona mas também é fixado no
  build, e o runtime recusa em voz alta. A forma que funcionaria — uma segunda leva de imagens —
  custa um artefato paralelo à demo e **ainda assim não devolveria** a comparabilidade com os
  025–028, porque aquele código mudou desde então. O mecanismo ficou mais estreito de quebra: a
  chave desliga métrica e log de verdade, e falha só no trace, porque o `SdkTracerProvider` não
  tem o atalho "sem processador, vira no-op". Consequências escritas: os ~5% do 059 medem
  exportar os três sinais, gravar métrica e espelhar log — **não** a gravação de span, que os
  dois lados pagam —, e uma corrida do overlay só é comparável com outra corrida do overlay.

- [O `Scope` do `Rastro` abre numa thread e fecha noutra](tickets/063-escopo-do-rastro-atravessa-thread.md)
  — o risco era alcançável, e o "sem sintoma medido" do 061 era só sonda no lugar errado. Sondados
  os dez pontos de instrumentação dos três serviços com a suíte inteira: oito abrem sobre contexto
  duplicado, e dois não — `extracao.frames` (4 de 4 Extrações, fechando noutra thread nas 4, uma
  delas a `InnocuousThread-1` do pool comum da JVM) e `extracao.gravar-pacote` (3 de 3, fechando na
  mesma thread por acaso). Os dois chegam lá porque a cadeia segue na thread que completou o
  download do MinIO; o `videos` escapa porque devolve a continuação ao contexto de chamada, e o
  `@Scheduled` e o boot nem tocam no `Rastro`. Sintoma medido, não deduzido: `frames` e
  `gravar-pacote` nasciam filhos de `extracao.baixar-video` — span **já encerrado**, corrente
  porque o `close` de outra thread é ignorado em silêncio. A regra que fica: escopo só atravessa
  fronteira assíncrona preso ao contexto duplicado do Vert.x; fora disso, abre e fecha na mesma
  thread. Depois da troca, os dois nascem filhos de `extracao.extrair-video`.

- [A cauda de ack manual tem nome local em cada serviço](tickets/067-cauda-de-ack-repetida-nos-consumidores.md)
  — os quatro consumidores encerram o trabalho pela mesma forma nomeada: sucesso chama `ack()` e
  falha chama `nack(falha)`. Cada serviço mantém sua própria cópia package-private em `framework`,
  sem módulo compartilhado; no `extracao`, a mesma cópia atende o consumo normal e o da DLQ,
  preservando o `failure-strategy=reject` que envia tentativas esgotadas ao Estacionamento.

- [Workers não anunciam uma borda HTTP que não existe](tickets/068-framework-web-em-worker-sem-borda-http.md)
  — `ExtracaoConfiguration` e `NotificacaoConfiguration` são raízes de composição CDI e moram
  em `framework.configuration`; só o `videos`, que expõe a borda pública, mantém
  `framework.web`. Uma guarda idêntica nos três serviços proíbe o pacote web em qualquer worker.

## Ainda não especificado

<!-- O 024 fechou o caminho até o *destino*: tudo que o enunciado cobra está entregue. A
     fronteira reabriu por escolha — `docs/arquitetura.md` § Limitações conhecidas declarava
     que "a escalabilidade é argumentada, não medida", e sobrava prazo para converter a frase
     em número. O 025 converteu a metade da conservação, e o número reprovou; o 026 converteu a
     metade da linearidade, e o número confirmou; o 027 corrigiu o que o 025 condenou e remediu;
     o 028 converteu a última metade, a da borda, e o número quase confirmou. As quatro células
     da tabela § *O que escala, e como* estão medidas agora — nenhuma pergunta sharp restante
     daquela rodada de escolha.

     A fronteira reabriu de novo, em 2026-09-01, por uma revisão de arquitetura sobre
     `develop @ 6128f33` que perguntou onde mora a máquina de estados do Vídeo. O que ela
     achou não foi vazão: foi o ADR 0002 descrevendo um desenho de duas perguntas das quais
     só uma rodava, e dois pontos sem fundo no caminho de recuperação quando o `extracao`
     cai. Os cinco tickets desta rodada saem daí, e a ordem entre eles é a ordem do risco:
     decidir e medir a recuperação primeiro, mexer no código do `videos` depois. O 029 e o 030
     já fecharam (ver Decisões até aqui); dos três que continuam abaixo, um ainda é decisão
     (033), um é código testado sem chamador em produção (031) e um é código sem teste (032).
     O 030 virou o 035, que continua a pergunta contra fonte primária: o que a sessão fechou
     foi que a premissa original do 030 estava errada, não que o buraco fechou. -->

- **[031](tickets/031-decisao-de-transicao-em-java.md) — a decisão de transição roda em Java.**
  `transitaPara` e os `marcaComo*` têm zero chamadores em `src/main`: a suíte de use case inteira
  valida uma implementação que não embarca. Emenda o ADR 0002 em duas frentes — a entidade entra
  no caminho de produção, e terminal→terminal deixa de ser bug para ser corrida de rede.
- **[032](tickets/032-ciclo-da-extracao-no-extracao.md) — o ciclo da Extração mora no
  `extracao`.** A regra permanente contra transitória está dentro de um adapter de 280 linhas
  sem teste. São duas máquinas de estado e só uma está modelada.
- **[033](tickets/033-iniciada-em-morto.md) — o `iniciadaEm` descartado.** Atravessa três
  camadas sem destino, e é exatamente a coluna que faltaria para varrer `PROCESSANDO` preso.
  Espera o 029 para saber se o cenário sobrevive à configuração.

<!-- 035 nasceu do 030, na mesma sessão de 2026-09-04 que o fechou: a leitura do código-fonte
     do conector e do `quarkus-arc` desmentiu a premissa de que `stop_grace_period` bastasse. -->

- **[035](tickets/035-drenar-extracao-antes-do-sigterm.md) — drenar a Extração em voo antes
  do `SIGTERM`.** Concluído: o observador CDI cancela a assinatura `extrair-video` antes de
  esperar o ack, mantendo o canal e os publicadores abertos. A ponte usa campos privados do
  SmallRye e rejeita no boot versões diferentes da 4.32.1; atualizar exige repetir o ensaio.
  Duas réplicas, 12 Vídeos de dois minutos: 12 concluídos, zero reentregas novas, redeploy em
  4s; antes da correção, a mesma carga gastou uma reentrega. Cancelamento e espera dividem
  os 420s do dreno, abaixo dos 480s do Docker. SIGKILL e falhas de rede continuam fora.

- **[038](tickets/038-override-de-canal-por-variavel-quebra-o-boot.md) — boot corrigido e
  `mata-publicacao` medido.** Overrides por variáveis próprias `FIAPX_*`, resolvidas no
  `.properties`, preservam `extracao-falhou` sem inventar canal na enumeração do SmallRye.
  Quatro réplicas subiram; três envios aceitos e três Vídeos em `PROCESSANDO`: critérios
  1 e 2 aprovados. Critério 3 reprovado: zero mensagens novas no estacionamento em 241s
  (limite 240s). A garantia do 029 permanece pendente de diagnóstico; o 038 resolve o boot
  e permite ao harness julgar os três critérios de verdade.

- **[039](tickets/039-dubles-de-transicao-nao-guardam.md) — o dublê de `VideoGateway` volta a
  guardar.** As três guardas em memória aplicam a transição do domínio à linha armazenada, e
  `buscarPorId` devolve cópia: sem ela o use case movia o próprio objeto do mapa e a guarda
  chegava sem nada para julgar. O flag `proximaTransicaoMudaLinha` saiu; a corrida perdida se
  arma por id, no instante da leitura. As três transições ganharam teste de corrida — antes só
  `falha` tinha, e nenhum reprovava. A unicidade do e-mail do ADR 0001 volta a ser provada
  pela suíte unitária.


- **[041](tickets/041-isolar-espaco-por-tentativa-de-extracao.md) — o scratch passou a ser por
  tentativa, não por Vídeo.** `prepararNovo` abre `{idVideo}-{sufixo}` atômico e `limpar`
  recebe o caminho daquela tentativa, não o id: com o nome derivado só do Vídeo, duas réplicas
  com o mesmo comando duplicado apagavam os frames uma da outra sobre o volume compartilhado.
  Medido no Compose com duas réplicas: antes, o h264 válido terminou em `FALHOU`
  /`ARQUIVO_INVALIDO` com os dois desfechos publicados para o mesmo Vídeo; depois, `CONCLUIDO`
  e Pacote íntegro pela borda pública, sem sobra no volume. O ensaio virou script
  (`scripts/carga/duplicata-em-replicas.sh`). A varredura de órfãos do 027 ganhou precisão de
  graça — passa a julgar tentativa, não Vídeo — e ganhou um gatilho periódico (`@Scheduled`,
  15 min): sem o apaga-e-recria, o boot sozinho não alcança o órfão da réplica que morreu e
  voltou, o que foi medido no volume depois do ensaio de conservação.


<!-- Recusadas nesta rodada, com o motivo, para a recusa não virar esquecimento: **banco no
     `extracao`** (tentativa como entidade durável) — reverte o `AGENTS.md`, e o Dono lê o estado
     por HTTP no `videos`, então o estado voltaria replicado, trocando um bug de ordenação medido
     por um de replicação não medido. **`extracao` notificando o Dono direto** — sem banco não há
     token de idempotência, e a reentrega é garantida por contrato: moveria a garantia do ADR 0001
     para o único serviço estruturalmente incapaz de fornecê-la. **Um terminal só em vez de três**
     — é a única mudança de contrato com defeito medido atrás dela (o 027), mas quebra o ticket 007
     e `docs/contratos/mensagens.md`; fica de fora até o contrato abrir. -->

<!-- A fronteira reabriu de novo em 2026-09-05, e desta vez não por medição: por uma
     revisão de dois eixos sobre `3a3ec95...b4672ff` — o intervalo inteiro do projeto, do
     ticket 002 ao 047. O eixo Standards julgou o código contra o `AGENTS.md`, os contratos,
     os ADRs e o baseline de smells; o eixo Spec julgou-o contra `docs/enunciado.md` e os
     próprios tickets. Oito tickets saíram daí, e a ordem entre eles é a ordem do risco: os
     dois P1 eram requisito do enunciado não exercido e ADR desmentido pelo código; os dois P2
     são janela de reconciliação assimétrica e vocabulário ambíguo atravessando fronteira; os
     quatro P3 são manutenção e registro. Os oito já fecharam (ver Decisões até aqui). Um
     achado foi recusado: a cerca do 045 é sintática e `Uni.join` passaria verde, mas o
     próprio 045 já registra isso como escolha barata deliberada, e reabrir seria refazer
     decisão registrada. -->

## Fora de escopo

<!-- ruled beyond the destination; nunca gradua -->

- **Painel curado e canal de notificação de alerta** — o que resta fora depois do redesenho
  de destino de 06/09/2026. A recusa original era mais larga: *"Prometheus + Grafana com
  dashboards — o enunciado lista monitoramento como stack recomendada, não como requisito
  técnico obrigatório. Com 5,5 semanas solo, é o primeiro candidato a canibalizar o tempo do
  CI/CD. Health checks continuam dentro."* Ela valeu enquanto o CI/CD era risco; entregue o
  CI/CD e esvaziada a fronteira, a coleta dos três sinais entrou (tickets 058–060). Ficaram
  de fora as duas partes que continuam custando sem pagar nesta entrega: **painel curado**
  (a exploração ad-hoc responde as mesmas perguntas sem manutenção) e **canal de notificação
  de alerta** (os alertas existem e guardam histórico; entregá-los por e-mail é configuração
  de *contact point*, adiada conscientemente — a detecção não muda, e isso está registrado
  em Limitações conhecidas).
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
- **Ferramental de agente versionado** — [ticket 074](tickets/074-remover-o-ferramental-de-agente-versionado.md)
  tirou `.agents/`, `.claude/skills/` e `skills-lock.json` do rastreamento e os pôs no
  `.gitignore`, revertendo a metade do [ticket 055](tickets/055-registrar-as-escolhas-fora-do-enunciado.md)
  que os defendia no README — a outra metade, o `.devcontainer/`, continua versionada e
  justificada. As skills são instaladas por ferramenta externa e vivem na instalação global de
  quem trabalha aqui; 138 arquivos de processo entre o clone e o código dos três serviços
  custavam mais atenção do avaliador do que pagavam. O que a entrega guarda é o resultado do
  fluxo — este mapa, os tickets, os ADRs —, não a ferramenta que o produziu.
