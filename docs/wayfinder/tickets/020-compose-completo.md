# Compose completo: orquestração dos cinco serviços

- id: 020
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por: 009, 011, 013, 014, 015, 016, 017, 018

## Question

Todas as dependências de cada serviço já estão confirmadas (tickets 009, 011, 013, 014, 015,
016, 017, 018) — o que falta é montar o `docker-compose.yml` que sobe os cinco serviços
(`videos`, `extracao`, `notificacao`, Keycloak, mais Postgres/RabbitMQ/MinIO/MailHog) na ordem
certa, com seed de cada dependência.

Requisitos já fechados que este ticket precisa honrar (ver `## Ainda não especificado` do
mapa antes de começar, é o resumo consolidado):

- Postgres: `POSTGRES_DB=fiapx_videos`, `docker/postgres/init.sql` montado em
  `docker-entrypoint-initdb.d` (ticket 009).
- MinIO: seed de dois buckets (`videos`, `pacotes`) com regra de ciclo de vida de 7 dias em
  ambos; volumes nomeados `fiapx-uploads` (`/var/fiapx/uploads`, serviço `videos`) e
  `fiapx-extracao-scratch` (`/var/fiapx/extracao`, serviço `extracao`, dono 185, folga de
  4 GB) (ticket 011, ticket 015).
- Imagens: os três serviços entram como `image: ghcr.io/vandrep/fiapx-<servico>:latest`,
  **sem** chave `build:` — `target/` está no `.gitignore` (ticket 013).
- `videos`: precisa de `POSTGRES_DB`, MinIO, RabbitMQ e Keycloak de pé antes de subir, roda
  com `%prod` (`schema-management.strategy=validate` derruba o boot se `init.sql` divergir
  das entidades) (ticket 016).
- RabbitMQ: `rabbitmq:4.3.5-management-alpine`; topologia (exchanges, filas quorum, DLQs) é
  toda declarada pelo próprio `videos` e pelo próprio `extracao` na subida — o
  `definitions.json` do Compose só carrega o que não é queue argument: a policy
  `dead-letter-strategy=at-least-once` e os usuários do broker (ticket 017, ticket 015).
- `extracao`: mesmas variáveis de RabbitMQ e MinIO que `videos`, **sem** `POSTGRES_DB` nem
  OIDC; imagem já carrega ffmpeg (~467 MB) (ticket 015).
- `notificacao`: `rabbitmq-host=rabbitmq`, `%prod.quarkus.mailer.host=mailhog`,
  `%prod.quarkus.mailer.port=1025`, `%prod.quarkus.mailer.mock=false` (sem essa última linha
  o serviço volta a mockar o envio mesmo em produção); sem `POSTGRES_DB` nem OIDC (ticket 014).
- Keycloak: importa `docker/keycloak/realm-export.json` (já existe, criado no ticket 016).

Decisões que este ticket ainda precisa fechar:

- **Ordem de subida por health check** — `depends_on: condition: service_healthy` entre os
  cinco serviços e as quatro dependências de infra; qual serviço espera qual.
- **Seed do RabbitMQ** (`definitions.json`) — usuários do broker e a policy
  `dead-letter-strategy=at-least-once`, nada além disso (a topologia é auto-declarada).
- **Seed do MinIO** — como os dois buckets e a lifecycle rule de 7 dias entram no Compose
  (init container? `mc` num serviço one-shot?).
- **Realm do Keycloak, decisões ainda abertas** (fog original, dobrada aqui por acoplamento
  direto com o Compose): precisa de audience mapper (sem ele, não configurar
  `token.audience`)? Qual o elenco final de clients e usuários que a banca vê, além de
  `demo`/`outro` já no `realm-export.json`?
- **Networking** — uma rede só, nomes de serviço como hostname (já assumido pelos tickets:
  `rabbitmq`, `mailhog`).
- **Portas expostas ao host** — quais (Swagger UI do `videos`, consoles de MinIO/RabbitMQ/
  Keycloak/MailHog para a demo) vs. quais ficam só na rede interna.

Consultar `grilling` e `domain-modeling` por padrão (ver Notas do mapa).

## Resolução

`docker-compose.yml` na raiz, mais `docker/rabbitmq/{definitions.json,conf.d/10-definitions.conf}`
e `docker/minio/seed.sh`. Verificado ponta a ponta contra os cinco serviços de verdade — não só
`docker compose config`: upload autenticado → RabbitMQ → `extracao` roda ffmpeg de verdade →
`CONCLUIDO` → download do Pacote com os frames reais, e o caminho de falha (arquivo inválido →
`FALHOU` → e-mail no MailHog) — ambos funcionando de fato, não assumidos a partir da config.

Decisões do ticket, na ordem em que a grelha as fechou:

- **Credenciais do Postgres**: `POSTGRES_USER=fiapx`/`POSTGRES_PASSWORD=fiapx` (senha de demo),
  injetadas no `videos` como `QUARKUS_DATASOURCE_USERNAME`/`_PASSWORD`.
- **Usuário do RabbitMQ**: `guest` só aceita loopback — não autentica entre containers. Seed cria
  o usuário `fiapx`/`fiapx`, administrator, permissions completas em `/`; a policy
  `dead-letter-strategy=at-least-once` aplica com `pattern: ".*"`.
- **Seed do MinIO**: serviço one-shot `minio/mc`, `depends_on: minio: service_healthy`; os três
  serviços de negócio esperam `minio-seed: service_completed_successfully`.
- **Health checks**: mecanismo nativo por imagem (`pg_isready`, `rabbitmq-diagnostics -q ping`,
  `curl` no MinIO, `wget` no MailHog e nos três serviços de negócio via `/q/health/ready`,
  truque `/dev/tcp` no Keycloak — a imagem não traz `curl` nem `wget`).
- **Realm do Keycloak**: sem audience mapper, roster atual (`fiapx-videos`, `demo`, `outro`)
  mantido — é o que os cenários Cucumber já usam.
- **Portas expostas**: `videos` 8080 (Swagger), `keycloak` 8081, `rabbitmq` 15672, `minio` 9001,
  `mailhog` 8025; `extracao`/`notificacao`/`postgres`/API do MinIO só na rede interna.
- **Keycloak**: `quay.io/keycloak/keycloak:26.7.1` (mesma versão dos Dev Services do Quarkus
  3.31.3), `start-dev --import-realm` — destino é demo, não hardening de produção.

Três achados que só apareceram testando o Compose de verdade — nenhum teste unitário ou Cucumber
publica mensagem real pela fila, nem todos os três serviços sobem juntos antes deste ticket:

- **`videos` não tinha nenhuma config de datasource** (nem `jdbc-url`, nem usuário, nem senha) —
  faltava porque é Hibernate **Reactive**, não JDBC: a chave certa é
  `quarkus.datasource.reactive.url` (formato `postgresql://host:porta/db`, sem prefixo `jdbc:`),
  não `quarkus.datasource.jdbc.url`. O erro do boot ("Datasource '<default>' was deactivated
  automatically") é o sintoma.
- **`videos` e `extracao` tinham `credentials.type=static` mas nunca declaravam as chaves de
  credencial** (`static-provider.access-key-id`/`secret-access-key`) — adicionadas em `%prod`
  nos dois `application.properties`, resolvidas por `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`.
- **O maior**: nenhum dos seis consumidores `@Incoming` das três aplicações sobrevivia a uma
  mensagem publicada de verdade. O conector RabbitMQ decodifica todo payload
  `content-type: application/json` em `io.vertx.core.json.JsonObject` — nunca no tipo do record
  do canal —, e o invoker gerado fazia um cast direto, explodindo em `ClassCastException` a cada
  mensagem. `quarkus-jackson` sozinho não bastava (resolve simetria com Vert.x's `JsonObject`,
  mas o invoker não consulta o `MessageConverter` chain para esse caso). A correção: um
  `JsonObjectPayloadConverter implements io.smallrye.reactive.messaging.MessageConverter` — não
  `org.eclipse.microprofile.reactive.messaging.spi.MessageConverter`, que não existe nesta versão
  — em `framework.dispatcher` de cada serviço (não há módulo `shared`), fazendo
  `((JsonObject) payload).mapTo(target)`. `quarkus-jackson` entrou no `pom.xml` raiz (comum aos
  três) porque `mapTo` depende de Jackson databind no classpath, e só o `videos` já o tinha via
  `quarkus-rest-jackson`.
- **Issuer do Keycloak e Swagger UI são endereços diferentes**: `auth-server-url` (validado pelo
  backend) é `keycloak:8080`, inalcançável de fora do Compose; sem `KC_HOSTNAME` fixo, o issuer
  seguiria o Host header de quem pediu o token, e o `videos` rejeitaria qualquer token pedido de
  fora. `KC_HOSTNAME=http://keycloak:8080` fixa o issuer; `FIAPX_OPENAPI_TOKEN_URL` (lido em
  runtime via `ConfigProvider`, não em build-time) aponta o botão Authorize do Swagger — que roda
  no browser do avaliador — para a porta publicada (`localhost:8081`), um valor diferente do
  issuer que só um override em runtime permite desacoplar.

Fora do automatizado: não há teste de integração do Compose no CI (ticket 020 não altera a
decisão do mapa de não rodar e2e no GitHub Actions). Os três achados acima não têm regressão
automatizada — ver ticket 021 (script de smoke) para fechar essa lacuna.
