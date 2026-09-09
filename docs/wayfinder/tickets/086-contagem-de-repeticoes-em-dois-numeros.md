# A contagem do ADR 0001 está implementada em dois números

- id: 086
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P2

## Origem

Achado dos dois eixos da revisão de `1ebefcb...57da6fc`, convertido em ticket com aprovação do
usuário. Os dois eixos chegaram nele por caminhos independentes.

## O problema

O [ADR 0001](../../adr/0001-politica-de-falhas.md), emendado no
[061](061-travamento-raro-com-o-sdk-desligado.md), diz:

> a retentativa nos *adapters* de I/O continua sendo a mesma política — três tentativas, espera
> de 2 s, só sobre `Exception`

Quatro lugares citam essa frase. Eles a implementam em dois números diferentes:

| Onde | Constante | `atMost` | Chamadas ao recurso |
|---|---|---|---|
| `videos/.../ArquivoMinioClient` | `MAXIMO_DE_REPETICOES = 3` | `atMost(3)` | **4** |
| `extracao/.../ArquivoMinioClient` | `MAXIMO_DE_REPETICOES = 3` | `atMost(3)` | **4** |
| `notificacao/.../MailerEmailClient` | `MAXIMO_DE_REPETICOES = 3` | `atMost(3)` | **4** |
| `videos/.../framework/db/PostgresRetry` | `MAX_RETRIES = 2` | `atMost(2)` | **3** |

`atMost(n)` do Mutiny conta repetições *depois* da primeira chamada. As três cópias de
`comRepeticao` fazem 4 chamadas; o `PostgresRetry` faz 3.

**Nenhum dos dois lados é obviamente o defeito**, e é por isso que isto é ticket e não conserto
direto:

- As três cópias preservam, número por número, o que o `@Retry(maxRetries=3)` do MicroProfile
  fazia antes do 061 — e o javadoc das três diz "3 repeticoes", que é a leitura certa do que
  elas fazem.
- O `PostgresRetry` diz de si "As tres tentativas totais [...] sao a politica do ADR 0001", e o
  commit `61ac28d` ("corrige limite do retry no postgres", ticket 057) existe **precisamente**
  para levá-lo de 4 chamadas a 3.

Uma sessão leu "três tentativas" como três chamadas; a outra leu como três repetições. As duas
leram a mesma frase.

**A raiz é vocabular, e o `CONTEXT.md` já a decidiu do outro lado.** Ele reserva *tentativa*
para outra coisa:

> Uma **tentativa** é uma *entrega* do trabalho ao serviço `extracao`, não um erro.

Essa é a contagem do `x-delivery-limit=3` da fila, não a do retry dentro de um adapter. O ADR
0001 gasta a mesma palavra nas duas contagens, e enquanto ele disser "tentativas" onde quer
dizer repetições, os dois números vão continuar parecendo ambos corretos.

O [085](085-espera-do-retry-nas-outras-duas-copias.md) fechou com o critério "a contagem de
repetições continua constante nas três cópias: ela é a política do ADR 0001" — verdadeiro entre
as três, e sem alcance sobre a quarta.

Sobra ainda um comentário que descreve essa política e a arquiva no lugar errado, em
`videos/src/main/resources/application.properties`:

> O limite (3 tentativas, espera de 2s) e a classificacao de falhas ficam no adapter, conforme
> ADR 0001

O limite e a classificação ficam em `framework/db/PostgresRetry`, não no adapter, e o comentário
não configura chave nenhuma.

## O que entregar

Uma aritmética só, escrita uma vez e citada pelos quatro lugares.

1. **A decisão de quantas chamadas**, 3 ou 4, com o motivo. O peso a considerar é que o número
   multiplica a espera de 2 s: no cenário de recurso persistentemente fora, 4 chamadas seguram o
   consumidor por 6 s antes de devolver a falha, e 3 por 4 s. O 085 mediu esse custo na suíte.
2. **As quatro constantes alinhadas** à decisão, com a mesma aritmética visível em cada uma —
   isto é, se a decisão for "3 chamadas", as três cópias de `comRepeticao` vão a `atMost(2)`.
3. **O ADR 0001 reescrito** para dizer *repetição* onde conta repetições, deixando *tentativa*
   com o sentido único que o `CONTEXT.md` já lhe dá. A emenda entra como texto novo do ADR, e
   não como reescrita silenciosa da frase antiga.
4. **O comentário do `application.properties` do `videos`** corrigido ou removido: se ficar, diz
   o número certo e aponta para `PostgresRetry`.

Se a decisão for manter os dois números diferentes de propósito — o Postgres é o recurso mais
caro de segurar, e há argumento para ele repetir menos —, então ela vale igual, e o que entrega
é o motivo escrito no ADR mais a frase que hoje sugere uma política única desfeita.

## Critérios de aceite

- [x] As quatro constantes de repetição concordam com a mesma leitura do ADR 0001, ou a
      diferença entre elas está escrita no ADR com o motivo
- [x] O ADR 0001 não usa mais *tentativa* para contar repetições de I/O
- [x] `CONTEXT.md` § *tentativa* continua valendo sem emenda: nenhuma frase nova disputa a
      palavra com ele
- [x] O javadoc do `PostgresRetry` e o das três cópias de `comRepeticao` declaram o mesmo número
      de chamadas ao recurso, na mesma palavra
- [x] O comentário do Postgres no `application.properties` do `videos` aponta para onde o limite
      mora, ou saiu
- [x] `PostgresRetryTest` e os dois testes de repetição (`RepeticaoNoMinioTest`,
      `RepeticaoNoSmtpTest`) afirmam a contagem decidida, e falham se ela mudar
- [x] `./mvnw test` verde a partir da raiz, com `ffmpeg` no host e o Keycloak do Compose parado

## Resolução

**A decisão: três chamadas ao recurso — a primeira mais duas repetições, `atMost(2)` nos
quatro lugares.** É a leitura que o [057](057-retry-transitorio-no-postgres.md) já tinha
aplicado ao `PostgresRetry`; o que muda no código são as três cópias de `comRepeticao`, que
saíram de `MAXIMO_DE_REPETICOES = 3` para `2` e passaram de 4 chamadas ao recurso para 3.

O motivo está escrito no ADR 0001, e é o peso que o próprio ticket mandou considerar: o número
multiplica a espera de 2 s. Quatro chamadas seguram quem chamou por 6 s antes de devolver a
falha, três por 4 s — atrás da borda HTTP do `videos` isso é o cliente esperando um `500` já
decidido, e nos dois workers é a réplica com `max-outstanding-messages=1` sem consumir mais
nada. A quarta chamada só compra o blip que durou mais que duas esperas, e a indisponibilidade
mais longa que isso é assunto do `x-delivery-limit=3` da fila, que reentrega o trabalho
inteiro, e não de uma repetição a mais dentro do adapter.

O que entrou:

- **O ADR 0001 ganhou uma emenda nova**, com a aritmética escrita uma vez (`atMost(n)` conta as
  repetições *depois* da primeira chamada, logo três chamadas são `atMost(2)`), o motivo da
  escolha e a lista dos quatro lugares que a citam. A frase antiga do 061 não foi reescrita:
  ela ganhou um parêntese que manda ler *três chamadas ao recurso* e aponta para a emenda —
  o registro do que se decidiu na época fica, e a palavra deixa de contar repetição sozinha.
- **`CONTEXT.md` não foi tocado.** *Tentativa* continua sendo só a entrega do trabalho ao
  `extracao`; o ADR é que parou de disputar a palavra.
- **Os quatro javadocs falam a mesma aritmética na mesma palavra** — "3 chamadas ao recurso: a
  primeira mais 2 repetições" —, e as três cópias de `comRepeticao` continuam idênticas entre
  si. O `PostgresRetry` perdeu o "três tentativas totais"; o resto do vocabulário dele
  (`MAX_RETRIES`, `DELAY`, inglês) é do [087](087-postgresretry-diverge-das-copias-de-comrepeticao.md)
  e ficou de fora de propósito.
- **O comentário do `application.properties` do `videos`** ficou, corrigido: diz 3 chamadas,
  aponta para `framework/db/PostgresRetry` em vez do adapter e avisa que não configura chave
  nenhuma.
- **Os três testes travam a contagem.** `RepeticaoNoMinioTest` e `RepeticaoNoSmtpTest` passaram
  a cobrar 3 chamadas no cenário do recurso persistentemente fora (cobravam 4); os cenários de
  blip falham as duas primeiras chamadas, então sucedem na última que a política permite e
  reprovam tanto se a repetição sumir quanto se sobrar uma. `PostgresRetryTest` já cobrava 3 e
  ganhou a palavra: `esgotaDepoisDeTresChamadasAoBanco`, contador `chamadas`, mensagem em cada
  asserção.

Efeito colateral medido em relógio: os dois cenários de "armazenamento persistentemente fora"
do `EnvioResisteABlipDoArmazenamentoTest` passaram a gastar uma espera a menos cada. Com a
espera de 1 ms do perfil de teste isso não aparece na suíte; em produção são 2 s a menos por
falha definitiva de I/O.

## Revisão

O `/code-review` de `a9db71f..ff9d3da` rodou os dois eixos. O que ele achou e o que virou
mudança, no mesmo commit:

- **Corrigido — afirmação falsa sobre o cenário de blip.** A `## Resolução` acima e o javadoc
  dos dois testes diziam que o blip "reprova tanto se a repetição sumir quanto se sobrar uma".
  A segunda metade é falsa: com duas falhas iniciais, um `atMost(3)` ainda sucederia na terceira
  chamada e o cenário passaria. Quem guarda o teto é o cenário do recurso persistentemente fora,
  que conta as chamadas até a desistência. O javadoc dos dois testes passou a dizer isso.
- **Corrigido — a aritmética estava recontada nas três cópias.** O javadoc de `comRepeticao`
  repetia, nas três, a narrativa histórica do 086 e o cálculo inteiro, logo depois de afirmar
  que a aritmética mora no ADR. O texto encolheu: fica o número, a leitura de `atMost(n)` que o
  leitor do código precisa, e o ponteiro para o ADR. As três seguem idênticas entre si.
- **Corrigido — três linhas passaram de 100 colunas** nas edições de javadoc
  (`videos/.../ArquivoMinioClient`, `EnvioResisteABlipDoArmazenamentoTest`). Não há checkstyle
  no build; a largura é convenção lida do código ao redor.
- **Verificado, e o achado não procede.** O eixo Standards questionou a frase do ADR de que o
  057 levou o `PostgresRetry` "de quatro chamadas a três", por não estar no ticket 057. Está no
  commit: `61ac28d` muda `MAX_RETRIES` de 3 para 2, que é exatamente 4 chamadas para 3.
- **Mantido, com o motivo.** A frase antiga do 061 ganhou um parêntese em vez de ficar intocada.
  O ticket pedia que a emenda não fosse "reescrita silenciosa da frase antiga": ela não é
  silenciosa — o parêntese diz que a palavra mudou e aponta para a emenda —, e sem ela o ADR
  continuaria contando repetição de I/O com *tentativa* na frase mais lida do documento.
- **Mantido, com o motivo.** Sobraram duas ocorrências de *tentativa* no ADR (linhas 7 e 76):
  as duas contam **entregas** — a citação da pesquisa do 003 e o backoff durável por TTL/DLX
  nas *Considered Options* —, que é o sentido do `CONTEXT.md`. O critério é sobre contar
  repetição de I/O, e nenhuma das duas faz isso.
- **Registrado como lacuna, não resolvido aqui.** *Repetição* e *chamada ao recurso* viraram
  vocabulário canônico do ADR e do código, e o `CONTEXT.md` não tem verbete para nenhum dos
  dois. O critério de aceite proibia disputar a palavra *tentativa*, e não disputamos; dar
  verbete próprio aos dois termos é trabalho de `domain-modeling` e precisa de ticket.
- **Escopo além dos quatro entregáveis, e por quê.** `EnvioResisteABlipDoArmazenamentoTest`
  não está nomeado no ticket, mas o javadoc dele afirmava "3 repeticoes" e contava o custo de
  relógio com uma espera a mais nos cenários de armazenamento fora — os dois viravam falsos com
  a decisão. Mesmo motivo para o javadoc de `abrirSeExistir`, que dizia "gasta tentativa".

`./mvnw test` a partir da raiz, com `ffmpeg` no host e o `fiapx-v2-keycloak-1` do Compose
parado: **BUILD SUCCESS em 3:42, 450 testes** (141 `videos`, 280 `extracao`, 29 `notificacao`),
0 falhas.
