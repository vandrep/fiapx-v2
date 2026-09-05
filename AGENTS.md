# AGENTS.md

FIAP X: três serviços Quarkus em Clean Architecture, um repositório, um build Maven.
`videos` é a borda pública e dona do estado; `extracao` e `notificacao` são workers.

## Idioma

Responda sempre em português — no chat, em PR, em issue, em comentário de review. Vale
para qualquer agente que trabalhe neste repositório. Commits seguem a convenção própria,
na seção Commits abaixo.

## Onde a verdade mora

As regras de camada **não estão escritas aqui** — estão em
`<servico>/src/test/java/br/com/fiapx/architecture/ArchitectureConstraintsTest.java`, que
as verifica lendo os fontes. `./mvnw test` reprova quem as violar, com o arquivo e a regra
na mensagem. Leia o teste antes de escrever a primeira classe de uma camada nova; ele é a
autoridade, e este arquivo só carrega o que ele não consegue dizer.

O resto do contexto está atrás de ponteiros, cada um com o seu gatilho:

| Quando você for | Leia |
|---|---|
| precisar da visão de conjunto do sistema, ou explicá-lo a alguém de fora | [`docs/arquitetura.md`](docs/arquitetura.md) — aponta, não repete |
| usar qualquer termo do domínio em código, endpoint ou mensagem | [`CONTEXT.md`](CONTEXT.md) — glossário canônico |
| mexer em endpoint, status HTTP ou corpo de resposta do `videos` | [`docs/contratos/http-videos.md`](docs/contratos/http-videos.md) |
| publicar ou consumir mensagem, ou mexer em fila, exchange ou DLQ | [`docs/contratos/mensagens.md`](docs/contratos/mensagens.md) |
| escrever um consumidor `@Incoming` ou um publicador | [`docs/contratos/mensagens.md` § Camadas](docs/contratos/mensagens.md) — o template não cobria mensageria |
| tocar em retry, dead-letter ou notificação duplicada | [ADR 0001](docs/adr/0001-politica-de-falhas.md) |
| tocar em transição de estado do Vídeo | [ADR 0002](docs/adr/0002-maquina-de-estados-em-duas-camadas.md) |
| tocar em publicação de comando ou de falha no `videos` | [ADR 0003](docs/adr/0003-reconciliacao-por-varredura.md) |

## Layout

```
pom.xml         parent agregador, packaging pom, br.com.fiapx:fiapx — não gera artefato
videos/         pom + src + Dockerfile
extracao/       idem
notificacao/    idem
```

Pacote base `br.com.fiapx`. Cada serviço carrega **exatamente um** módulo de negócio,
homônimo do serviço — `br.com.fiapx.videos.core`, `br.com.fiapx.extracao.core`. O teste
arquitetural cobra essa unicidade: um segundo módulo de negócio dentro de um serviço quebra
o build.

O parent carrega o BOM do Quarkus, as versões de plugin e só as dependências comuns aos três
(`quarkus-arc`, `quarkus-smallrye-health`, ferramental de teste). Extensão de negócio vai no
pom do serviço que a usa.

Os três serviços partem do mesmo template e ainda carregam as classes `Item*` de exemplo.
Elas são referência viva de cada padrão de classe e saem conforme cada serviço é implementado.

## O que difere entre os três serviços

| | `videos` | `extracao` | `notificacao` |
|---|---|---|---|
| Banco | Postgres + Panache | nenhum | nenhum |
| Borda HTTP | pública, sob OIDC | nenhuma | nenhuma |
| Fora do JVM | — | `ffmpeg` por **processo externo** | SMTP |

Só `videos` pode declarar `@WithTransaction`, `@WithSession` ou um `DataSourceAdapter`.
Nos outros dois, persistência é ausência de requisito, não pendência.

`extracao` invoca `ffmpeg` como processo externo e classifica a falha pelo exit code.
JavaCV foi medido e recusado: 3,5× mais lento e 5× mais memória — a medição está em
[`docs/pesquisa/ffmpeg-extracao.md`](docs/pesquisa/ffmpeg-extracao.md).

## As três cópias do teste arquitetural

`ArchitectureConstraintsTest` existe em três cópias, uma por serviço, e elas são
**byte a byte idênticas** — inclusive `MODULO_DO_SERVICO`, que é derivado do nome do
diretório do módulo em vez de fixado. Mantenha-as assim: `scripts/verifica-testes-arquiteturais.sh`
roda na fase `validate` do agregador e reprova o build na primeira divergência. Editou uma,
edite as três.

Não há módulo `test-support` de propósito: o teste vale porque é legível no lugar, aberto
ao lado do código que julga. A guarda é o preço disso.

Duas asserções do template chegaram relaxadas aqui, e continuam assim: as que exigiam existir
ao menos um `Resource` e ao menos um `DataSourceAdapter`. Foram escritas para um monólito
onde toda camada está sempre populada; por serviço, elas acusam `notificacao` por não ter
borda HTTP e os dois workers por não terem banco. As guardas de `core`, controller e use case
seguem duras.

Uma terceira mudou no ticket 016: método de `Resource` pode devolver **`Uni` ou `RestMulti`**,
não só `Uni`. Não é afrouxamento — o handler de streaming do RESTEasy Reactive olha o retorno
**direto** do método, então o download do Pacote não tem como devolver `Uni`: um `Multi`
embrulhado em `Response` pendura a conexão, e embrulhado em `Uni` sai serializado pelo
`toString()` do objeto. Os dois foram medidos. O que a regra protege — nada de retorno
bloqueante na borda — os dois tipos cumprem igualmente.

Uma quarta regra chegou no ticket 017, endurecendo em vez de relaxar: `@Incoming`,
`@Outgoing` e `@Scheduled` só podem aparecer em `framework` (mensageria e agendamento são
infraestrutura, igual a `@ApplicationScoped` ou `@Path`). O template não trazia essa regra
porque não cobria mensageria nem scheduler — ver
[`docs/contratos/mensagens.md` § Camadas](docs/contratos/mensagens.md).

Uma quinta chegou no ticket 034, e é a primeira que não julga código Java: todo canal
`mp.messaging.outgoing.*` do `application.properties` do próprio serviço precisa declarar
`publish-confirms=true` no mesmo prefixo, a não ser que declare um `connector` que não seja
`smallrye-rabbitmq`. A obrigação recai também sobre o canal **sem** `connector` explícito, de
propósito: com um conector só no classpath — o caso dos três serviços — o Quarkus liga o canal
mudo ao RabbitMQ do mesmo jeito.
Sem confirms o `send` completa quando o byte sai no socket, não quando o broker aceita — a
recusa vira ack e a mensagem some em silêncio, e a varredura do
[ADR 0003](docs/adr/0003-reconciliacao-por-varredura.md) não alcança o Vídeo perdido
([ADR 0001](docs/adr/0001-politica-de-falhas.md),
[`docs/contratos/mensagens.md`](docs/contratos/mensagens.md)). O default do conector é `false`,
e o mesmo buraco já nasceu duas vezes, em dois serviços (027, 029), achado nas duas por medição
ou revisão manual. Como cada cópia do teste roda com o CWD no seu módulo, a regra cobre os três
`application.properties` sem que o teste precise enxergar o diretório do vizinho — e vale para
`notificacao`, que hoje não publica: ela protege o serviço, não o canal que existe.

O limite conhecido dela: a regra lê `application.properties`, então canal ou override que
chegue por variável de ambiente (`MP_MESSAGING_OUTGOING_*`, como o overlay de carga faz em
`docker-compose.carga.yml`) passa por fora. Overrides de Compose são deliberados e revisados
junto do arquivo que os declara; o defeito que este teste persegue é o canal esquecido no
`.properties`.

## BDD

Cenários de aceite em Gherkin **em português** (`# language: pt` na primeira linha), em
`<servico>/src/test/resources/features/`. Os steps exercitam a borda pelo RestAssured e
nunca chamam use case ou gateway direto: o que o BDD valida é comportamento observável de
fora. Cada fluxo principal ganha ao menos um `.feature` antes de ser considerado pronto.

## Rodar

`./mvnw test` **a partir da raiz**, e com **Docker de pé** — hoje `videos` sobe Dev Services
de Postgres, e cada extensão nova (RabbitMQ, Keycloak, S3) acrescenta um container. Sem
Docker o build falha por timeout de container, não por código quebrado.

O `extracao` também precisa de **`ffmpeg`/`ffprobe` no `PATH` do host** que roda o teste
(ticket 006, ticket 015): o pipeline chama o binário via `ProcessBuilder`, mesmo em teste —
não há dublê. O `runner-images` do `ubuntu-latest` não traz ffmpeg por padrão, por isso o CI
o instala explicitamente antes do `verify` (`.github/workflows/ci.yml`).

Rodar Maven na raiz também é o que dispara a guarda das três cópias: ela está presa ao
agregador, então `mvn -f videos/pom.xml` a pula silenciosamente.

**Não escreva `*IT.java`.** Não existe nenhum, e é de propósito: `skipITs` é `true` no
parent, então o failsafe não roda e `verify` não acrescenta nada a `test`. Teste integrado
aqui é `@QuarkusTest` no surefire — ele sobe os Dev Services de verdade, que é o que
importa. O failsafe só ganharia sentido para testar o artefato **empacotado**
(`@QuarkusIntegrationTest`, imagem nativa), que está fora de escopo.

O CI (`.github/workflows/ci.yml`) roda `./mvnw verify` a partir da raiz num job só, e
publica as três imagens no GHCR quando o commit entra na `main`.

**O `verify` não prova que os três serviços conversam** — cada suíte testa um serviço
isolado, com Dev Services próprios. O fluxo ponta-a-ponta contra o Compose de verdade é
`scripts/smoke.sh`, e ele fica fora do CI de propósito. Rode-o quando mexer em contrato,
mensageria, config de Compose ou imagem: é a única coisa no repo que reprova um serviço que
passa nos próprios testes e mesmo assim não fala com o vizinho. `verify` em vez de `test`
porque o CI precisa do `package` para construir as imagens no mesmo runner.

`scripts/carga/conservacao.sh` é o outro degrau: rajada de centenas de envios contra o Compose
com falha injetada (`docker kill` no `extracao` ou no `videos`), julgada por critérios fixados
antes de rodar. Rode-o quando mexer em máquina de estados, consumo de evento ou reconciliação —
ele reprova onde o `smoke.sh` passa, porque o `smoke.sh` manda um vídeo de cada vez. Hoje ele
**reprova de propósito**: três defeitos medidos e ainda abertos, em
[`docs/wayfinder/tickets/027-melhorias-medidas.md`](docs/wayfinder/tickets/027-melhorias-medidas.md).

## Commits

Conventional Commits em português, sem acentos na linha de assunto:

```
feat: adiciona envio de video autenticado
fix: corrige transicao de estado concorrente
docs: resolve ticket 012, agents md da raiz
```

Commits de wayfinding citam o ticket no assunto, como acima.

## Branches

O trabalho vai todo na **`develop`** — branch única, de vida longa. **Não crie branch por
ticket.** Commit direto na `develop`; PR `develop` → `main` quando a fatia estiver pronta.

A `main` é protegida por ruleset: push direto é rejeitado, PR é obrigatório (zero
aprovações) e o status check `build` precisa passar. Por isso `develop` também dispara o
CI no push — sem isso o primeiro sinal de quebra só chegaria ao abrir o PR, com vários
tickets acumulados. Imagem no GHCR só é publicada a partir da `main`.

Branch por ticket dava de graça um ponto fixo para o `/code-review` (a merge-base com a
`main`). Numa `develop` de vida longa esse ponto some: anote o SHA com `git rev-parse HEAD`
**antes** de começar o ticket e revise com `/code-review <sha>`.
