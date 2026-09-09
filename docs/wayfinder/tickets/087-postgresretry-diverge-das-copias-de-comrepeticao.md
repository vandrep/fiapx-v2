# `PostgresRetry` é a mesma forma que `comRepeticao`, e nenhum registro diz isso

- id: 087
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] O vocabulário do `PostgresRetry` concorda com o das três cópias, ou a diferença está
      escrita
- [ ] A costura que baixa a espera sob teste é a mesma nas quatro, ou a diferença está escrita
- [ ] A decisão sobre jitter no Postgres está escrita, qualquer que seja
- [ ] `transitoria` não classifica por `endsWith` sobre nome de classe, ou a razão de classificar
      assim está no javadoc
- [ ] As três travessias de `getCause()` estão unificadas onde a arquitetura permite, e o que
      sobrar está registrado como família de cópia deliberada
- [ ] Nenhuma chave de tolerância a falhas por interceptor entra em `.properties` nenhum:
      `toleranciaAFalhasNaoPodeSerConfigurada` segue verde nos três
- [ ] `scripts/verifica-testes-arquiteturais.sh` e `scripts/verifica-ackmanual.sh` passam
- [ ] `./mvnw test` verde a partir da raiz
