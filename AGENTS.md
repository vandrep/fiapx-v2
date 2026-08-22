# AGENTS.md

FIAP X: três serviços Quarkus em Clean Architecture, um repositório, um build Maven.
`videos` é a borda pública e dona do estado; `extracao` e `notificacao` são workers.

## Onde a verdade mora

As regras de camada **não estão escritas aqui** — estão em
`<servico>/src/test/java/br/com/fiapx/architecture/ArchitectureConstraintsTest.java`, que
as verifica lendo os fontes. `./mvnw test` reprova quem as violar, com o arquivo e a regra
na mensagem. Leia o teste antes de escrever a primeira classe de uma camada nova; ele é a
autoridade, e este arquivo só carrega o que ele não consegue dizer.

O resto do contexto está atrás de ponteiros, cada um com o seu gatilho:

| Quando você for | Leia |
|---|---|
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

## BDD

Cenários de aceite em Gherkin **em português** (`# language: pt` na primeira linha), em
`<servico>/src/test/resources/features/`. Os steps exercitam a borda pelo RestAssured e
nunca chamam use case ou gateway direto: o que o BDD valida é comportamento observável de
fora. Cada fluxo principal ganha ao menos um `.feature` antes de ser considerado pronto.

## Rodar

`./mvnw test` **a partir da raiz**, e com **Docker de pé** — hoje `videos` sobe Dev Services
de Postgres, e cada extensão nova (RabbitMQ, Keycloak, S3) acrescenta um container. Sem
Docker o build falha por timeout de container, não por código quebrado.

Rodar Maven na raiz também é o que dispara a guarda das três cópias: ela está presa ao
agregador, então `mvn -f videos/pom.xml` a pula silenciosamente.

**Não escreva `*IT.java`.** Não existe nenhum, e é de propósito: `skipITs` é `true` no
parent, então o failsafe não roda e `verify` não acrescenta nada a `test`. Teste integrado
aqui é `@QuarkusTest` no surefire — ele sobe os Dev Services de verdade, que é o que
importa. O failsafe só ganharia sentido para testar o artefato **empacotado**
(`@QuarkusIntegrationTest`, imagem nativa), que está fora de escopo.

O CI (`.github/workflows/ci.yml`) roda `./mvnw verify` a partir da raiz num job só, e
publica as três imagens no GHCR quando o commit entra na `main`. `verify` em vez de `test`
porque o CI precisa do `package` para construir as imagens no mesmo runner.

## Commits

Conventional Commits em português, sem acentos na linha de assunto:

```
feat: adiciona envio de video autenticado
fix: corrige transicao de estado concorrente
docs: resolve ticket 012, agents md da raiz
```

Commits de wayfinding citam o ticket no assunto, como acima.
