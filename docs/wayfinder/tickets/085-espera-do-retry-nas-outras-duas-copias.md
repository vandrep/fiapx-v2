# A espera do retry ficou configurável só numa das três cópias

- id: 085
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P3

## Origem

Medido durante o [ticket 080](080-custo-de-teste-do-blip-sem-substituto.md), registrado lá como
fora de escopo e convertido em ticket com aprovação do mantenedor.

## O problema

O 080 tornou a espera entre repetições configurável por perfil no `ArquivoMinioClient` do
`videos`, e o cenário do blip caiu de 26,46 s para 6,17 s. Ele mediu, de passagem, que as outras
duas cópias de `comRepeticao` pagam o mesmo custo pela mesma aritmética:

| Teste | Cópia | Tempo |
|---|---|---|
| `RepeticaoNoMinioTest` | `extracao/.../ArquivoMinioClient` | **14,30 s** |
| `RepeticaoNoSmtpTest` | `notificacao/.../MailerEmailClient` | **10,19 s** |

Nas duas, `ESPERA_ENTRE_REPETICOES` continua `private static final Duration.ofSeconds(2)`, sem
perfil que a altere — exatamente o estado de que o `videos` saiu.

`comRepeticao` é uma das cinco famílias do `AGENTS.md` § *As cópias deliberadas entre serviços*,
que manda inspecionar todas as cópias ao mudar a parte comum e aplicar "somente o que preserva o
mesmo contrato". O 080 **inspecionou e mediu** — essa metade está cumprida —, mas não aplicou, e
com isso a divergência foi *introduzida*: hoje uma cópia lê a espera de configuração e duas a têm
fixa. O mesmo parágrafo diz "não as force a convergir", o que dá cobertura à decisão de esperar;
por isso isto é ticket, e não defeito.

## O que entregar

Uma das duas, decidida e escrita:

- A espera vira configurável nas outras duas cópias, no mesmo desenho do `videos`: chave no
  namespace `fiapx.` do próprio serviço, **default no código** e não no `.properties`, e override
  só no perfil de teste. As três cópias voltam a ter a mesma forma.
- Ou as duas ficam como estão, e o motivo vai escrito — no `AGENTS.md` § *As cópias deliberadas
  entre serviços*, que é onde a divergência legítima entre cópias se registra.

Se for a primeira, vale notar que o piso é **1 ms e não zero**: o Mutiny recusa backoff zero com
`IllegalArgumentException` na subscrição, achado do 080 que custou uma medição.

## Critérios de aceite

- [x] O custo dos dois cenários está medido antes e depois, com número
- [x] A escolha entre ajustar e manter está registrada, com o motivo
- [x] Nenhuma chave de tolerância a falhas por interceptor entra em `.properties` nenhum — a
      sétima regra do teste arquitetural (`toleranciaAFalhasNaoPodeSerConfigurada`) segue verde
      nos três
- [x] A contagem de repetições continua constante nas três cópias: ela é a política do
      [ADR 0001](../../adr/0001-politica-de-falhas.md), e só a espera é preço
- [x] As três cópias de `comRepeticao` têm a mesma forma, ou a diferença está escrita no
      `AGENTS.md`
- [x] `./mvnw test` verde a partir da raiz. Exige `ffmpeg` instalado no host — sem ele o
      `extracao` reprova 5 cenários por um motivo que não é o seu código, e o 080 descreve os
      quatro sintomas diferentes que a mesma causa produz

## Resolução

**Escolhida a primeira das duas opções: a espera virou configurável nas outras duas cópias**, no
mesmo desenho que o [080](080-custo-de-teste-do-blip-sem-substituto.md) estreou no `videos`. As
três voltam a ter a mesma forma, e a divergência que o 080 introduziu deixa de existir.

**O custo, medido antes e depois, na mesma máquina:**

| Teste | Espera de produção (2 s) | Espera de teste (1 ms) |
|---|---|---|
| `RepeticaoNoMinioTest` (`extracao`) | **14,34 s** | **0,25 s** |
| `RepeticaoNoSmtpTest` (`notificacao`) | **10,18 s** | **0,15 s** |

Os dois números de antes reproduzem os que o 080 mediu (14,30 s e 10,19 s), e a soma bate com a
aritmética da política: 3 repetições × 2 s no cenário de recurso persistentemente fora, 2 × 2 s no
cenário de blip. **24,1 s de relógio saíram da suíte** por dois cenários que não julgam a duração
da espera.

**Por que ajustar e não manter.** O motivo é o mesmo que o 080 escreveu, e as duas classes o dizem
de si mesmas no javadoc: o que está sob julgamento é a repetição *acontecer* e a última falha
chegar ao chamador — as duas asserções são sobre a **contagem de chamadas** e sobre o tipo da
exceção, nunca sobre tempo. Nenhuma delas mede a espera, então nenhuma paga por ela. Que os 2 s do
ADR 0001 sejam 2 s continua guardado pelo default do `@ConfigProperty`.

**Como.** `ESPERA_ENTRE_REPETICOES` saiu de `private static final Duration.ofSeconds(2)` e virou
campo injetado, no namespace `fiapx.` do próprio serviço:
`fiapx.armazenamento.espera-entre-repeticoes` no `extracao` (que já é dono de
`fiapx.armazenamento.bucket-*`) e `fiapx.notificacao.espera-entre-repeticoes` no `notificacao`. O
prefixo do segundo é o do serviço, e não o do recurso, pelo precedente do repositório:
`fiapx.armazenamento.` existe porque o MinIO é compartilhado entre `videos` e `extracao`, e o SMTP
só o `notificacao` alcança — então a chave mora onde as outras chaves de um serviço só moram
(`fiapx.extracao.*`). O
**default de 2 s vive no código**, e não no `.properties`, para que a espera de produção sobreviva
a um arquivo de configuração incompleto. `comRepeticao` deixou de ser `static` para alcançar o
campo, exatamente como no `videos`. `MAXIMO_DE_REPETICOES` **continua constante** nas três: a
contagem é a política do [ADR 0001](../../adr/0001-politica-de-falhas.md), e só a espera é preço.

**Uma diferença de forma que não entrou, e o motivo.** O `videos` baixa a espera por
`%test.` no `application.properties`; estas duas **não têm essa linha**. A diferença é do teste, e
não da cópia: o cenário do `videos` é um `@QuarkusTest` que entra pela borda HTTP e recebe o bean
do CDI, enquanto `RepeticaoNoMinioTest` e `RepeticaoNoSmtpTest` montam o bean a mão — sem
container, por decisão anterior a este ticket — e por isso atribuem o campo direto. Como nenhum
`@QuarkusTest` do `extracao` ou do `notificacao` injeta blip no MinIO ou no SMTP, um `%test.`
nesses dois arquivos **não teria leitor**: seria chave morta, que é pior do que ausência. O código
das três cópias de `comRepeticao` tem a mesma forma, então o quinto critério está cumprido pela
primeira metade; ainda assim a diferença **está escrita** no `AGENTS.md` § *As cópias deliberadas
entre serviços*, porque é ali que a próxima sessão que mexer na parte comum vai procurar — e no
javadoc do campo, em cada uma das duas.

**O piso é 1 ms e não zero**, como o 080 avisou: o Mutiny recusa backoff zero com
`IllegalArgumentException` na subscrição. As duas classes de teste declaram
`ESPERA_DO_TESTE = Duration.ofMillis(1)` com essa razão em comentário, para que ninguém tente
"arredondar" de volta.

**A sétima regra segue verde nos três.** Nenhuma chave entrou em `.properties` nenhum neste
ticket, e as que existem no `videos` e as duas novas são configuração de bean no namespace
`fiapx.`, não chave de tolerância a falhas por interceptor —
`toleranciaAFalhasNaoPodeSerConfigurada` lê os namespaces proibidos, e nenhum deles foi tocado.

**Validações**: `./mvnw test` a partir da raiz **verde — 450 testes, zero falhas**, duas vezes: uma
antes e outra depois de o code-review renomear a chave do `notificacao` (141 no
`videos`, 280 no `extracao`, 29 no `notificacao`), com `ffmpeg` 7.1.5 no host e o
`fiapx-v2-keycloak-1` do Compose parado durante a corrida (a porta 8081 é a mesma do
`@QuarkusTest`). `scripts/verifica-testes-arquiteturais.sh` e `scripts/verifica-ackmanual.sh`
passam. As medições de antes e depois da tabela são de corridas isoladas por módulo, para ficarem
comparáveis entre si; dentro da suíte inteira, com a JVM quente, as duas classes levam 0,088 s e
0,055 s.
