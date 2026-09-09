# A contagem do ADR 0001 está implementada em dois números

- id: 086
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] As quatro constantes de repetição concordam com a mesma leitura do ADR 0001, ou a
      diferença entre elas está escrita no ADR com o motivo
- [ ] O ADR 0001 não usa mais *tentativa* para contar repetições de I/O
- [ ] `CONTEXT.md` § *tentativa* continua valendo sem emenda: nenhuma frase nova disputa a
      palavra com ele
- [ ] O javadoc do `PostgresRetry` e o das três cópias de `comRepeticao` declaram o mesmo número
      de chamadas ao recurso, na mesma palavra
- [ ] O comentário do Postgres no `application.properties` do `videos` aponta para onde o limite
      mora, ou saiu
- [ ] `PostgresRetryTest` e os dois testes de repetição (`RepeticaoNoMinioTest`,
      `RepeticaoNoSmtpTest`) afirmam a contagem decidida, e falham se ela mudar
- [ ] `./mvnw test` verde a partir da raiz, com `ffmpeg` no host e o Keycloak do Compose parado
