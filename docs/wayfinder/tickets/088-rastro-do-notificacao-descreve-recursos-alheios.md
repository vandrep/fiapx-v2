# O `Rastro` do `notificacao` descreve recursos que o serviço não tem

- id: 088
- label: ready-for-agent
- status: fechado
- assignee: agente
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `1ebefcb...57da6fc`, convertido em ticket com aprovação
do usuário.

## O problema

O `AGENTS.md` § *As cópias deliberadas entre serviços* manda não forçar as cópias a convergir, e
dá justamente esta razão:

> o `Rastro`, por exemplo, documenta recursos externos diferentes

O `notificacao/.../framework/observabilidade/Rastro.java` não fez essa parte. A seção
*Onde `emTorno` vale a pena, e onde não* chegou do `extracao` quase inteira:

> A mensageria e o Postgres aparecem sozinhos. O MinIO **não**: a extensão da AWS arrasta o
> `opentelemetry-aws-sdk-2.2` e monta o `AwsSdkTelemetry`, mas **nenhum span de S3 chegou ao
> Tempo** num ciclo completo de Vídeo (ticket 059, verificado no `smoke.sh`).

O `notificacao` não tem Postgres — o `AGENTS.md` § *O que difere* registra o banco dele como
nenhum — e não fala com o MinIO. Dois terços do parágrafo descrevem a auto-instrumentação de
outro serviço.

A última frase, essa sim, é do serviço certo: "O que fica sem dono aqui e o SMTP do
quarkus-mailer, ultimo trecho da travessia de um Video que falhou." É ela que justifica o
`emTorno` que a classe realmente usa.

O defeito é de registro, não de comportamento: nenhum span muda. O que muda é o que a próxima
sessão acredita ao abrir o arquivo — e o [061](061-travamento-raro-com-o-sdk-desligado.md) já
descartou uma hipótese inteira por acreditar numa frase desatualizada deste mesmo javadoc, o que
o próprio arquivo narra algumas linhas acima.

## O que entregar

O parágrafo reescrito para os recursos externos que o `notificacao` de fato alcança, preservando
a forma e a intenção da seção — *onde `emTorno` vale a pena aqui, e onde não*. A frase sobre o
SMTP fica.

Vale conferir, de passagem, se o `Rastro` do `videos` tem o mesmo problema ao contrário: ele tem
Postgres e MinIO, então o parágrafo pode estar certo lá por acidente e não por edição.

## Critérios de aceite

- [x] O javadoc do `Rastro` do `notificacao` não afirma nada sobre Postgres nem sobre MinIO
- [x] A frase sobre o SMTP como trecho sem dono continua, e continua sendo o que justifica o uso
      de `emTorno` na classe
- [x] Nenhuma afirmação de medição ("verificado no `smoke.sh`") é atribuída a um serviço em que a
      medição não foi feita
- [x] O `Rastro` do `videos` foi conferido contra os recursos do `videos`
- [x] As três cópias continuam divergindo só no que o `AGENTS.md` autoriza divergir; nada de
      comportamento mudou
- [x] `./mvnw test` verde a partir da raiz

## Resolução

Os três `Rastro` passaram a descrever cada um os recursos externos do seu próprio serviço.
Nenhum span mudou: a alteração é toda de javadoc.

### `notificacao`

A seção *Onde `emTorno` vale a pena, e onde não* foi reescrita para os dois recursos que o
serviço alcança. A mensageria fica do lado coberto — o conector RabbitMQ abre o span de
recebimento sozinho, e o que falta nele é duração, do que cuida `naMensagem` e não `emTorno`. O
SMTP fica do lado sem dono, com a frase original preservada e agora com uma razão verificável no
lugar da medição alheia: **não há artefato de instrumentação para o cliente de mail no classpath
deste serviço**. Conferido por `dependency:list -DincludeGroupIds=io.opentelemetry.instrumentation`
no `notificacao` — só `instrumentation-api`, as `annotations`, `runtime-telemetry` e um
`opentelemetry-jdbc` que nada usa. Nenhum `opentelemetry-aws-sdk-2.2`, que é o que confirma que
o parágrafo sobre o MinIO era do `extracao`.

Mais dois trechos do mesmo arquivo descreviam recursos alheios, e foram junto:

- o javadoc de `emTorno` prometia "MinIO, SMTP" e um "span de servidor HTTP da borda" — o
  `notificacao` não tem borda HTTP, então o pai é **sempre** o span de `naMensagem`;
- a frase sobre quem carrega o span pelos saltos de thread citava o worker pool do `@Blocking` e
  a thread do SDK da AWS. O consumo deste serviço **não tem** `@Blocking` — o próprio
  `VideoFalhouConsumer` diz isso no javadoc dele —, e não há SDK da AWS. Ficou o cliente de mail
  reativo. A analogia com a sessão do Panache saiu no mesmo movimento: o banco deste serviço é
  nenhum.

O bullet do ticket 063 manteve a regra e perdeu os exemplos alheios: aqui nada dentro de
`emTorno` lê o contexto corrente, porque não há instrumentação de mail para lê-lo.

### `videos` (o que o ticket mandou conferir de passagem)

O parágrafo estava certo **por edição, não por acidente** — ele cita `POST /videos`,
`INSERT video`, `SELECT video` e o vão mudo do upload de 200 MB, que são deste serviço e de
nenhum outro. Mas o javadoc de `emTorno` prometia "MinIO, SMTP", e o `videos` não fala SMTP:
ficou só o MinIO. No bullet do 063, o `ffmpeg` passou a vir atribuído ao `extracao`.

### `extracao` (achado fora do escopo do ticket, corrigido junto)

O parágrafo de origem tinha o **mesmo defeito**: "A mensageria e o Postgres aparecem sozinhos" —
e o `extracao` também tem "nenhum" na linha do banco em `AGENTS.md` § *O que difere*. O ticket
não notou porque citou esse trecho como se fosse do serviço certo. Ficou "A mensageria aparece
sozinha". O javadoc de `emTorno` de lá também prometia SMTP e borda HTTP, que o `extracao` não
tem; ficaram o MinIO e o `ffmpeg`.

### O que não foi tocado

`@Blocking` não aparece em código de produção de **nenhum** dos três serviços — só em javadoc, e
no `extracao` e no `notificacao` numa negação. A menção ao "worker pool do `@Blocking`" nas
cópias do `videos` e do `extracao` pode estar tão desatualizada quanto a do `notificacao`, mas
confirmar isso exige ler o roteamento de thread dos dois, que é outra investigação. Fica como
achado, não como conserto.

### Verificação

`./mvnw test` verde a partir da raiz: 141 `videos`, 280 `extracao`, 29 `notificacao`. O Compose
da demo foi parado antes e religado depois, pelo motivo de sempre (Dev Services do Keycloak
contra a porta 8081).

## Correção (revisão do 088)

O `/code-review` da entrega achou três erros **na `## Resolução` acima e no javadoc**, não no
código. O parágrafo errado fica onde está, como manda o `TRACKER.md` § *O que pode mudar num
ticket `fechado`*; o que ele diz de errado está aqui.

**1. A frase sobre `@Blocking` é falsa, e o achado estava mal delimitado.** A `## Resolução` diz
que "`@Blocking` não aparece em código de produção de nenhum dos três serviços". Aparece: o
`ExtrairVideoConsumer` do `extracao` importa `io.smallrye.common.annotation.Blocking` e anota o
consumidor na linha 86. O erro é de método — o `grep` que sustentou a frase foi truncado por um
`head` e a conclusão saiu do pedaço visível.

O achado corrigido é mais estreito, e mais útil: a menção ao worker pool do `@Blocking` no
`Rastro` do **`extracao` está certa** e não precisa de investigação nenhuma. Só a do **`videos`**
fica de pé como suspeita — lá `@Blocking` de fato não existe em produção. Continua sem conserto,
e agora com um alvo só.

**2. A evidência do classpath estava citada errada.** A `## Resolução` cita
`dependency:list -DincludeGroupIds=io.opentelemetry.instrumentation` como prova de que não há
`opentelemetry-aws-sdk-2.2` no `notificacao`. Esse comando **não discrimina**: o `aws-sdk-2.2` é
dependência *condicional* da extensão da AWS, resolvida na augmentação, então ele some do
`dependency:list` até no `extracao`, onde está presente de verdade. A prova certa é o app
aumentado, e ela foi refeita:

- `notificacao/target/quarkus-app/lib/main` — `instrumentation-api`, as `annotations`,
  `runtime-telemetry` e `opentelemetry-jdbc`. Nada de mail, nada de `aws-sdk-2.2`.
- `extracao/target/quarkus-app/lib/main` — os mesmos **mais**
  `opentelemetry-aws-sdk-2.2-2.23.0-alpha.jar`.

A conclusão não muda; ela fica mais forte, porque agora o contraste entre os dois serviços é
visível no mesmo comando.

**3. O javadoc enumerava o classpath, e errava por omissão.** A frase dizia que há "so a API do
OpenTelemetry e a telemetria de runtime" — e há também o `opentelemetry-jdbc`, que é artefato de
instrumentação. Num javadoc que existe justamente porque uma frase desatualizada custou uma
hipótese inteira ao [061](061-travamento-raro-com-o-sdk-desligado.md), enumerar o classpath é
assumir uma dívida sem necessidade. O texto passou a afirmar só o que sustenta o argumento e é
estável: não há artefato de instrumentação **para o cliente de mail**.
