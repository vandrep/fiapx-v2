# `PostgresRetry` é a mesma forma que `comRepeticao`, e nenhum registro diz isso

- id: 087
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por: 086
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `1ebefcb...57da6fc`, convertido em ticket com aprovação
do usuário. Reúne três achados que caem na mesma classe e no mesmo arquivo, agrupados a pedido
do usuário.

## O problema

O [057](057-retry-transitorio-no-postgres.md) criou `videos/.../framework/db/PostgresRetry`. O
`videos` passou a carregar **duas** implementações da mesma forma reativa:

```
Uni.createFrom().deferred(...) → .onFailure(...).retry() → .withBackOff(x, x) → .atMost(n)
```

uma em `PostgresRetry.executar`, outra em `ArquivoMinioClient.comRepeticao`. O `AGENTS.md`
§ *As cópias deliberadas entre serviços* registra cinco famílias — `Rastro`,
`JsonObjectPayloadConverter`, `comRepeticao`, `MotivoFalha.doCodigo` e `AckManual` — e todas as
cinco são cópias **entre serviços**. Este par é dentro do mesmo módulo, e não está em nenhuma
delas.

Não é pedido para fundir as duas: elas repetem por motivos diferentes (blip de I/O contra
indisponibilidade transitória do banco) e podem legitimamente divergir. O que falta é a
divergência estar **decidida e escrita**, como o `AGENTS.md` já exige das outras cinco. Hoje ela
aparece em três lugares, nenhum deles registrado:

**1. Vocabulário.** `PostgresRetry`, `MAX_RETRIES`, `DELAY`, `delay` são inglês; as três irmãs
usam `MAXIMO_DE_REPETICOES` e `esperaEntreRepeticoes`. O javadoc do `PostgresRetry` fala em
"tentativas" onde as irmãs insistem em "repetições" — a mesma ambiguidade que o
[086](086-contagem-de-repeticoes-em-dois-numeros.md) resolve, e por isso este ticket vem depois
dele.

**2. Costura de configuração.** As três cópias leem a espera de
`@ConfigProperty(name = "fiapx.…espera-entre-repeticoes", defaultValue = "2s")`, desenho que o
[080](080-custo-de-teste-do-blip-sem-substituto.md) estreou e o
[085](085-espera-do-retry-nas-outras-duas-copias.md) estendeu. O `PostgresRetry` recebe a espera
por um construtor package-private que só o teste chama. As duas resolvem o mesmo problema — não
pagar 2 s de relógio na suíte — por costuras diferentes, no mesmo módulo.

**3. Jitter.** As três cópias aplicam `withJitter(0.1)`, preservando o que o `@Retry` do
MicroProfile trazia por default. O `PostgresRetry` não aplica jitter nenhum. Para um recurso
com um pool de conexões compartilhado, a ausência de jitter é exatamente onde repetições
sincronizadas se empilham — pode ser deliberado, mas está por escrever.

**4. Classificação de falha por nome de classe.** `PostgresRetry.transitoria` decide por
`instanceof` nos três primeiros ramos e depois cai em comparação de string:

```java
var nome = atual.getClass().getName();
if (nome.endsWith("JDBCConnectionException")
        || nome.endsWith("LockAcquisitionException")
        || nome.endsWith("CannotCreateTransactionException")) {
```

`endsWith` sobre nome qualificado casa qualquer classe de qualquer pacote com aquele nome
simples, e não sobrevive a um rename dentro do Hibernate sem falhar em silêncio — o retry
simplesmente para de acontecer, e nada avisa. Se a razão for evitar dependência de compilação
com o Hibernate a partir de `framework/db`, ela vale; só não está escrita.

**5. Desembrulhar a cadeia de causas, três vezes.** A mesma travessia `getCause()` aparece em
`PostgresRetry.desembrulhar`, em `ProcessarExtracaoUseCase.causaRaiz` e no laço
`for (var causa = falha; …)` de `ExtrairVideoConsumer.metadadosDoNack`. Uma delas vive em
`core`, onde `framework` não alcança — então "unificar" pode ser impossível por desenho, e a
resposta certa ser registrar a sexta família. O que não serve é o estado atual, em que três
cópias existem e nenhum registro as conhece.

## O que entregar

Para cada um dos cinco pontos, uma das duas: **convergir** com as três cópias de `comRepeticao`,
ou **registrar** a divergência no `AGENTS.md` § *As cópias deliberadas entre serviços*, que é
onde a próxima sessão vai procurar. A decisão pode ser diferente em cada ponto — convergir
vocabulário e registrar jitter é um resultado legítimo.

Se o registro ganhar em algum ponto, ele diz o motivo, e não só o fato. O parágrafo que o 085
escreveu sobre o `%test.` ausente em dois `application.properties` é o modelo: ele explica *por
que* a diferença existe, então a próxima sessão não a "conserta".

Vale notar que o `AGENTS.md` § *cópias deliberadas* observa que quatro das cinco famílias não
têm guarda automática. Uma sexta família aumenta a dívida de guarda; se a decisão for registrar,
considerar se ela cabe no `scripts/verifica-ackmanual.sh` ou se fica declaradamente sem guarda.

## Critérios de aceite

- [x] O vocabulário do `PostgresRetry` concorda com o das três cópias, ou a diferença está
      escrita
- [x] A costura que baixa a espera sob teste é a mesma nas quatro, ou a diferença está escrita
- [x] A decisão sobre jitter no Postgres está escrita, qualquer que seja
- [x] `transitoria` não classifica por `endsWith` sobre nome de classe, ou a razão de classificar
      assim está no javadoc
- [x] As três travessias de `getCause()` estão unificadas onde a arquitetura permite, e o que
      sobrar está registrado como família de cópia deliberada
- [x] Nenhuma chave de tolerância a falhas por interceptor entra em `.properties` nenhum:
      `toleranciaAFalhasNaoPodeSerConfigurada` segue verde nos três
- [x] `scripts/verifica-testes-arquiteturais.sh` e `scripts/verifica-ackmanual.sh` passam
- [x] `./mvnw test` verde a partir da raiz

## Resolução

**Implementado.** Dos cinco pontos, quatro convergiram com as três cópias de `comRepeticao` e
um ficou registrado no `AGENTS.md`.

**1. Vocabulário — convergiu.** `PostgresRetry` virou `RepeticaoNoPostgres`, e
`PostgresRetryTest` virou `RepeticaoNoPostgresTest`, que é o nome que as irmãs já usavam
(`RepeticaoNoMinioTest`, `RepeticaoNoSmtpTest`). `MAX_RETRIES` virou `MAXIMO_DE_REPETICOES`, e
`DELAY`/`delay` viraram `esperaEntreRepeticoes`. O campo do `VideoDataSourceAdapter`, o
comentário do `application.properties` e as duas citações do ADR 0001 acompanharam; a primeira
citação do ADR diz o nome antigo entre parênteses, para quem chegar pelo histórico.

**2. Costura de configuração — convergiu.** Os dois construtores saíram e a espera passa a
vir de `@ConfigProperty(name = "fiapx.banco.espera-entre-repeticoes", defaultValue = "2s")`,
como nas três irmãs — default no código, para sobreviver a `.properties` incompleto
(ticket 080). Nenhum `%test.` foi acrescentado, pelo mesmo motivo do `extracao` e do
`notificacao` (ticket 085): o único teste que exercita a repetição monta o bean à mão e
atribui o campo direto, então a chave não teria leitor.

**3. Jitter — convergiu.** `withJitter(0.1)`, os mesmos 10% das três cópias. O javadoc diz por
que ele pesa mais aqui: o Postgres está atrás de um pool de conexões compartilhado, que é
exatamente onde repetições sincronizadas se empilham.

**4. Classificação por nome de classe — convergiu, e um dos três ramos era morto.** As duas
exceções do Hibernate passaram a `instanceof` com import: `hibernate-core` já está no classpath
de compilação pelo `quarkus-hibernate-reactive-panache`, então a razão que teria justificado o
`endsWith` não existia. A terceira, `CannotCreateTransactionException`, é
`org.springframework.transaction.*` — não há Spring neste repositório, e o ramo nunca casou
nada além de um homônimo; saiu. O teste novo
`naoConfundeUmaClasseHomonimaDeOutroPacoteComADoHibernate` fixa a diferença, e falhava contra o
código anterior.

**5. As três travessias de `getCause()` — não são uma família, e isso ficou escrito.** Elas
fazem perguntas diferentes: `desembrulhar` tira envelopes de `CompletionStage` até o primeiro
não-envelope, `causaRaiz` tira **um** nível, `metadadosDoNack` varre a cadeia inteira
procurando um tipo. O que havia de repetição de fato era o `causaRaiz` escrito duas vezes
dentro do `ProcessarExtracaoUseCase`, e essa unificou. Os três pontos ganharam javadoc
apontando para o registro.

**O registro.** O `AGENTS.md` § *As cópias deliberadas entre serviços* ganhou a sexta família —
a única que se repete **dentro** de um serviço —, o que convergiu, a divergência que sobrou (o
filtro de falha: qualquer `Exception` no MinIO contra só indisponibilidade transitória no
Postgres, e o `deferred(Supplier)` que reabre a sessão abortada) e o parágrafo sobre as
travessias de `getCause()`.

**Guarda: declaradamente nenhuma.** Não cabe no `scripts/verifica-ackmanual.sh` nem em variante
dele — aquele script compara texto e exige identidade, e o que diverge aqui diverge de
propósito, então uma comparação de texto acusaria exatamente a decisão. Quem guarda o
comportamento são os quatro testes de repetição.

**Validações.** `./mvnw test` a partir da raiz: **BUILD SUCCESS**, 143 testes no `videos`, 280
no `extracao`, 29 no `notificacao`. `scripts/verifica-testes-arquiteturais.sh` e
`scripts/verifica-ackmanual.sh` passaram, e o `ArchitectureConstraintsTest` — que é quem roda
`toleranciaAFalhasNaoPodeSerConfigurada` — passou nos três serviços. `smoke.sh` e os ensaios de
carga não foram executados: a mudança não toca contrato, mensageria, Compose nem imagem.
