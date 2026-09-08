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
| tocar em span, métrica, log estruturado ou na stack de observabilidade | [ADR 0004](docs/adr/0004-camada-de-observabilidade.md) — e § Nomes na observabilidade, abaixo |

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
chegue por variável de ambiente (`MP_MESSAGING_OUTGOING_*`) passa por fora. O overlay de
carga usa variáveis próprias `FIAPX_*`, referenciadas no `.properties`, para evitar a ambiguidade dos nomes
de canal com traço (ticket 038). Overrides de Compose são deliberados e revisados
junto do arquivo que os declara; o defeito que este teste persegue é o canal esquecido no
`.properties`.

Uma sexta chegou no ticket 061: **nada de tolerância a falhas por interceptor** em código de
produção — nenhum import de `org.eclipse.microprofile.faulttolerance` nem de
`io.smallrye.faulttolerance`. Não é preferência de estilo. Numa operação verdadeiramente
assíncrona, e aqui todas são, o interceptor monta `RememberEventLoop -> ThreadOffload` e
**reagenda a invocação no contexto Vert.x do chamador** — e esse reagendamento pode nunca
rodar. Nenhuma thread, nenhum socket, nenhuma retentativa, nenhuma linha de log, mensagem sem
ack para sempre. A explicação que encaixa é a ordenação daquele contexto (a chamada fica atrás
da cadeia que espera por ela); o que está *medido* é que a tarefa reagendada não roda. A
medição é do `extracao`; nos outros dois a regra vale por analogia estrutural, e é global
porque obedecê-la custa um operador do Mutiny e descobri-la por medição custa um travamento em
produção. Medido: 4 travamentos em ~60 ciclos com o interceptor, 0 em 90 sem ele
(`scripts/carga/travamento.sh`). O que substitui é `onFailure().retry()` do Mutiny, que
retenta dentro da própria cadeia — a política do [ADR 0001](docs/adr/0001-politica-de-falhas.md)
não mudou, só quem a implementa. A extensão saiu dos três `pom.xml` junto com a regra.

Uma sétima chegou no ticket 064, estendendo a sexta ao `application.properties`: nenhuma chave
de tolerância a falhas por interceptor pode ser configurada, nem no formato do MicroProfile
(`.../Retry/...` e as demais anotações conhecidas) nem no namespace `quarkus.fault-tolerance`
ou `smallrye.faulttolerance`. A sexta regra lê fonte Java, e uma chave de configuração não é
import nem anotação — ela sobrevive ao interceptor que saiu do `pom.xml`. Foi assim que duas
chaves `%test.../Retry/delay` sobreviveram no `application.properties` do `videos`,
configurando um `@Retry` que já não existia, com um comentário e um javadoc que ainda
descreviam a proteção antiga. É o mesmo ponto cego que o ticket 034 já havia fechado para
`publish-confirms`, aplicado agora à sexta regra em vez da quinta.

Uma oitava chegou no ticket 068: nenhum serviço além do `videos` pode declarar pacote
`framework.web`. `extracao` e `notificacao` não têm borda HTTP — a tabela "O que difere entre
os três serviços" traz "nenhuma" na linha da borda para os dois —, então o nome do pacote
promete um `Resource` que não existe e engana quem lê o código. `ExtracaoConfiguration` e
`NotificacaoConfiguration` são raízes de composição CDI, não borda de entrada; migraram para
`framework.configuration`, e a regra deriva o serviço do mesmo `MODULO_DO_SERVICO` que as
demais, então as três cópias seguem idênticas sem precisar de exceção por serviço.

O ticket 081 fechou a divergência que essa migração abriu: `VideosConfiguration` ficara sozinha
em `framework.web` e os três serviços passaram a nomear o mesmo papel de dois jeitos. Ela também
foi para `framework.configuration`. **A raiz de composição de qualquer serviço mora em
`framework.configuration`**; no `videos`, `framework.web` fica para o que é de fato borda HTTP —
`Resource`, mapeadores de `ProblemDetail`, filtro de OpenAPI. Nenhuma regra cobrava isso, e
nenhuma passou a cobrar: a regra oitava só proíbe `framework.web` nos workers, e uma guarda que
exigisse o pacote da configuração no `videos` cobraria layout que só tem um exemplo por serviço.

## As cópias deliberadas entre serviços

Além dos records do contrato de mensagens, cinco implementações se repetem entre serviços:
`Rastro` e `JsonObjectPayloadConverter` nos três, `comRepeticao` nos três clientes de I/O,
`MotivoFalha.doCodigo` em `videos` e `notificacao`, e `AckManual` nos três
(`framework/dispatcher/`). As cópias são deliberadas. Cada serviço continua dono do próprio
código e do próprio artefato; um módulo `shared` transformaria coincidência de implementação
em acoplamento de build e de evolução entre os três serviços.

Ao mudar a parte comum de uma dessas implementações, inspecione todas as cópias e aplique em
cada uma somente o que preserva o mesmo contrato. Não as force a convergir: o `Rastro`, por
exemplo, documenta recursos externos diferentes e só o de `videos` oferece `marcar`.

Não há guarda automática de divergência para quatro dessas cinco famílias — `Rastro`,
`JsonObjectPayloadConverter`, `comRepeticao` e `MotivoFalha.doCodigo`. Nenhuma delas tem
identidade byte a byte como invariante, e uma comparação parcial confundiria diferença local
legítima com esquecimento. Os testes de cada serviço guardam o comportamento; a revisão
coordenada guarda a parte comum.

`ArchitectureConstraintsTest` e `AckManual` são as exceções explícitas: nada no desenho de
nenhum dos dois sugere divergência local legítima, e por isso têm guarda do agregador. As três
cópias de `ArchitectureConstraintsTest` são byte a byte idênticas — inclusive
`MODULO_DO_SERVICO`, que é derivado em runtime do nome do diretório do módulo, não fixado por
cópia —, e a guarda em `scripts/verifica-testes-arquiteturais.sh` compara sem normalização. As
três cópias de `AckManual` diferem só na linha `package`, que carrega o nome do serviço; a
guarda em `scripts/verifica-ackmanual.sh` normaliza essa linha antes de comparar o resto.

## Nomes na observabilidade

Os três serviços exportam log, métrica e trace por OTLP (ticket 059). Os nomes têm **duas
origens, e duas regras** — não misture:

- **O que a auto-instrumentação emite fica como o OTel emite.** `http.route`,
  `messaging.destination.name`, os nomes de span do conector RabbitMQ e do SDK da AWS: são
  contrato com a ferramenta. Traduzir para o vocabulário do projeto quebra consulta e receita
  de ecossistema, e não compra nada em troca. Vale inclusive quando o nome soa feio ao lado
  do resto do código.
- **O que é nosso usa o vocabulário do [`CONTEXT.md`](CONTEXT.md).** A métrica própria é
  `fiapx.extracao.duracao`, com o atributo `resultado` em `concluida`/`falhou` — as palavras
  do glossário, não `success`/`error`. A mesma regra vale para atributo próprio de span e
  campo estruturado de log: `idVideo` é `idVideo`, como no contrato de mensagens.

Métrica nova precisa de justificativa igual à da primeira: existe uma só, e ela existe porque
mede um intervalo que roda fora do JVM e que nenhuma auto-instrumentação enxerga. O que já é
respondível pela auto-instrumentação ou por um endpoint não vira métrica.

Instrumentação vive **só em `framework`**, e quem cobra isso é o `ArchitectureConstraintsTest`
— a lista de imports e anotações proibidos está lá, que é a autoridade. O porquê de ela ter
sido endurecida no ticket 059 está no
[ADR 0004](docs/adr/0004-camada-de-observabilidade.md).

## BDD

Cenários de aceite em Gherkin **em português** (`# language: pt` na primeira linha), em
`<servico>/src/test/resources/features/`. Os steps entram pela **borda** e nunca chamam
controller, use case ou gateway direto: o que o BDD valida é comportamento observável de
fora. Cada fluxo principal ganha ao menos um `.feature` antes de ser considerado pronto.

Borda não é sinônimo de HTTP. Cada serviço tem a sua, e é por ela que o step entra:

| Serviço | Borda de entrada | Como o step entra | O que ele observa |
|---|---|---|---|
| `videos` | HTTP sob OIDC | RestAssured | status, corpo e cabeçalho da resposta |
| `extracao` | `fiapx.comandos`/`extracao.extrair` | AMQP puro (`com.rabbitmq.client`) | eventos em `fiapx.eventos` e o Pacote no MinIO |
| `notificacao` | `fiapx.eventos`/`video.falhou` | AMQP puro (`com.rabbitmq.client`) | o e-mail, pelo `MockMailbox` |

Nos dois workers isso é o cliente AMQP do teste no papel que o RestAssured cumpre no
`videos`: publica na routing key **real** do contrato e deixa o roteamento, a
desserialização e o consumidor de verdade rodarem. É o que o ticket 042 corrigiu — os
cenários chamavam o controller, então passavam com a entrada de mensageria quebrada.
**Não crie endpoint só para teste**: a mensagem já é a borda, e um `Resource` de teste
inventaria uma segunda, que produção não tem.

A única peça que existe só para o teste é a fila observadora do `extracao`
(`bdd.extracao-eventos`, exclusiva e auto-delete, ligada a `fiapx.eventos`): ela é o
análogo do cliente HTTP no lado da saída — em produção quem escuta essas routing keys é o
`videos`. Ela é exclusiva de propósito: o RabbitMQ 4.x barra fila transiente não exclusiva
(`transient_nonexcl_queues` está deprecado).

Isto continua sendo teste integrado no surefire, com infraestrutura real: `@QuarkusTest`
sobe o RabbitMQ dos Dev Services, e nada é dublado. Antes de publicar, o step espera a fila
do worker existir — um exchange `topic` sem binding descarta a mensagem em silêncio, e sem
essa espera o cenário reprovaria por corrida de boot em vez de por defeito.

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

`scripts/concorrencia.sh` é o degrau seguinte, e o único que julga um requisito do enunciado
em vez do fluxo: manda uma rajada de oito Vídeos contra o Compose padrão e reprova se a
listagem nunca mostrar dois em `PROCESSANDO` ao mesmo tempo. Rode-o quando mexer em réplica,
prefetch ou canal de entrada do `extracao` — é ele que segura a regressão do ticket 049, onde
a demo processava um vídeo por vez enquanto a documentação dava o requisito como atendido.

`scripts/carga/travamento.sh` persegue outro tipo de defeito: o que não aparece em uma
execução. Ele repete ciclos `POST /videos` → `CONCLUIDO` contra o Compose com teto por ciclo e,
no primeiro que estoura, coleta filas, *scratch* e thread dump **enquanto a réplica ainda está
presa** — que é o único momento em que a evidência existe. Rode-o quando mexer no consumidor de
`extracao.extrair`, nos adapters de I/O do `extracao` ou em qualquer coisa que reagende
trabalho entre threads. Foi ele que mediu o ticket 061, onde um travamento de 1 em ~15
Extrações passava por todos os outros scripts sem deixar uma linha de log.

`scripts/carga/conservacao.sh` é o outro degrau: rajada de centenas de envios contra o Compose
com falha injetada (`docker kill` no `extracao` ou no `videos`), julgada por critérios fixados
antes de rodar. Rode-o quando mexer em máquina de estados, consumo de evento ou reconciliação —
ele reprova onde o `smoke.sh` passa, porque o `smoke.sh` manda um vídeo de cada vez. Os dois
defeitos de correção que ele reprovava de propósito (terminal fora de ordem, marca do ADR 0003)
foram corrigidos no ticket 027; remedido contra o código atual no ticket 073, ele passa: 400/400
em `limpo`, 41/41 em `mata-videos` com o `videos` derrubado no meio da rajada.

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
