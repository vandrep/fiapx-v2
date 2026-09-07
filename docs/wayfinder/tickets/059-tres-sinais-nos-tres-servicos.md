# Os três sinais nos três serviços, correlacionados por idVideo

- id: 059
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por: 058
- prioridade: P1

## Origem

O ticket 058 põe a stack de pé e responde as perguntas de fila, mas não responde a que o
incidente de 06/09 realmente exigiu: *onde este Vídeo parou*. Para isso é preciso que os três
serviços emitam log, métrica e trace, e que a travessia inteira — borda HTTP, publicação,
consumo, `ffmpeg`, S3, publicação de volta — apareça costurada.

O contrato de mensagens já resolveu metade do problema sem saber:
`docs/contratos/mensagens.md` define `idVideo` como *"gerado pelo `videos` no upload;
correlaciona todas as mensagens"*. Ele é a chave que um humano digita; `trace_id` identifica
a **travessia**, que é outra coisa.

## O que entregar

Os três serviços exportam log, métrica e trace por OTLP para a stack do 058. Buscar por um
`idVideo` devolve o caminho completo daquele Vídeo pelos três serviços, com os registros de
log do caminho pendurados nos spans certos. A duração da Extração vira métrica de primeira
classe. O console dos containers continua legível por humano, exatamente como está hoje.

## Condições de aceite

- [ ] Exportação por OTLP nos três serviços, para os três sinais. Coleta **só** por OTLP: sem
  stdout em JSON e sem o coletor lendo `docker logs`.
- [ ] O console **não muda** e não é desligado. `docker logs` foi o que diagnosticou o
  incidente de 06/09, e o cenário em que ele mais importa é justamente aquele em que a stack
  de observabilidade é o que está quebrado.
- [ ] Amostragem em 100% (`always_on`). Amostrar destruiria o caso raro que motiva a camada,
  e o volume da demo é trivial — o pico de 400 Vídeos do overlay de carga não chega à stack,
  porque o 058 a desliga lá.
- [ ] Instrumentação **só em `framework`**: borda HTTP, dispatcher de mensagem e adapters de
  I/O. O `core` fica sem span — os spans de fronteira já contam a história inteira, e o
  `core` é síncrono e rápido o bastante para span ali ser ruído.
- [ ] `ArchitectureConstraintsTest` ganha `io.opentelemetry` e `io.micrometer` nas listas de
  import e anotação proibidos em `core` e `interfaces`. Hoje há um buraco: as listas são
  nominais e fechadas, então um `@WithSpan` num use case passaria em silêncio, e a regra
  estaria mentindo por omissão. **Editar as três cópias byte a byte** —
  `scripts/verifica-testes-arquiteturais.sh` reprova o build na primeira divergência.
- [ ] `idVideo` como atributo de span e campo estruturado de log em todo serviço que o
  conhece. Buscar por `idVideo` tem que levar ao trace.
- [ ] O contexto de trace atravessa o RabbitMQ por **header AMQP** (`traceparent`, W3C). Os
  cinco records do contrato ficam **intactos** — o Vídeo nunca trafega na mensagem, e o
  contexto de trace também não entra no corpo.
- [ ] Exatamente **uma** métrica própria de domínio: a duração da Extração. É os 98,2% do
  tempo de serviço que o ticket 027 mediu, roda dentro de um processo externo e por isso
  nenhuma auto-instrumentação a enxerga. Não acrescentar Vídeos-por-estado (o endpoint de
  listagem já responde, e um gauge exigiria varredura periódica no banco).
- [ ] Nomes: o que a auto-instrumentação emite fica como o OTel emite — é contrato com a
  ferramenta, e traduzir quebra o ecossistema. A métrica própria usa o vocabulário canônico
  do `CONTEXT.md`.
- [ ] `scripts/smoke.sh` ganha dois passos, coerentes com a doutrina do próprio arquivo
  ("todo passo é verificado, não só executado"): **(a)** consultar, pelo `idVideo` do Vídeo
  recém-processado, o trace correspondente, com laço de retentativa (a exportação é
  assíncrona); **(b)** derrubar a stack e provar que o ciclo do Vídeo continua íntegro. O (a)
  é a única prova de que a correlação funciona ponta a ponta — nada mais a exercita — e é
  também a tomada de demonstração que o 060 leva para o Bloco 2 do roteiro.
- [ ] Testes não exportam. A suíte roda a partir da raiz sem stack de observabilidade de pé.

## Dependências

Bloqueado pelo 058: não há para onde exportar antes que a stack exista.

## Resolução

**Implementado.** `quarkus-opentelemetry` entrou no pom **agregador**, e não nos três poms de
serviço: os três exportam os três sinais, então a extensão é comum aos três pela mesma regra
que já colocou `quarkus-arc` e `quarkus-smallrye-health` ali. Coleta só por OTLP; nenhum
arquivo de log mudou de formato e nenhum coletor lê `docker logs`.

**O que a auto-instrumentação dá de graça, e onde ela para.** Medido, não suposto: um ciclo
completo de Vídeo traz `POST /videos`, `INSERT video`, `SELECT video`, `UPDATE video`,
`fiapx.comandos publish`, `extracao.extrair receive` e os `fiapx.eventos publish` sem uma linha
de código nossa. Ela **para** em três lugares, e é exatamente onde a instrumentação própria
entrou:

1. **O trabalho do consumidor.** O conector RabbitMQ abre o span de recebimento a partir do
   `traceparent` e o **encerra na hora** — `TracingUtils.traceIncoming` chama `instrumenter.end`
   e fecha o escopo antes do método `@Incoming` rodar. Duas consequências, as duas fatais para o
   requisito: o span de recebimento tem duração ~0, e a publicação seguinte vira **raiz nova**,
   porque `traceOutgoing` usa `Context.current()` como pai. Sem costurar isso, o rastro se
   partiria em cada salto entre serviços.
2. **O `ffmpeg`**, que roda fora do JVM. Para o OpenTelemetry o `ProcessBuilder` é um buraco.
3. **O MinIO.** Este foi o achado que mudou uma decisão no meio do ticket: a extensão da AWS
   arrasta sozinha o `opentelemetry-aws-sdk-2.2` e monta o `AwsSdkTelemetry` assim que o
   OpenTelemetry entra no classpath — cheguei a **remover** os spans próprios de S3 por isso,
   invocando a regra de nomes deste ticket. Ao conferir o rastro no Tempo, **nenhum span de S3
   havia chegado**: o upload de 200 MB era um vão mudo dentro do span do POST. Os spans voltaram,
   agora com a medição escrita no javadoc em vez do argumento que a contradizia.

**A costura.** `framework/observabilidade/Rastro.java`, uma cópia por serviço (não há módulo
compartilhado, ticket 007). `naMensagem` pendura um span no contexto que veio na mensagem e o
mantém corrente durante **todo** o trabalho assíncrono; `emTorno` cobre o I/O sem dono. O
contexto sobrevive aos saltos de thread porque o `QuarkusContextStorage` o guarda no contexto
duplicado do Vert.x, não numa `ThreadLocal` — o mesmo mecanismo pelo qual o Panache acha a
sessão. Com o SDK desligado o span nasce sem gravar e o `Rastro` devolve a cadeia crua.

**Os quatro consumidores passaram a receber `Message` em vez do payload**, com
`@Acknowledgment(MANUAL)` explícito e ack/nack à mão — o `ExtrairVideoConsumer` já era assim
desde o ticket 035. Não é preferência: o contexto de trace chega no **header AMQP** e só é
alcançável pelo `Message`. O comportamento observável não muda; o nack continua caindo no
`failure-strategy` de cada canal. Os cinco records do contrato ficaram **intactos**, e o Vídeo
continua não trafegando na mensagem.

**Uma métrica própria**, `fiapx.extracao.duracao`, em segundos, com o atributo `resultado`
(`concluida`/`falhou`). O atributo não a torna duas métricas: sem ele, uma Extração que morre no
teto de 300 s do `ffmpeg` entraria na mesma distribuição das que terminaram, e a mediana passaria
a medir a mistura de duas populações. Vídeos-por-estado **não** entrou, como o ticket pedia.

**`ArchitectureConstraintsTest`** ganhou `io.opentelemetry` e `io.micrometer` na lista de import
e `WithSpan`, `SpanAttribute`, `AddingSpanAttributes`, `Counted` e `Timed` na de anotação — a
segunda lista existe porque anotação alcança o código também por import estático ou nome
qualificado. As três cópias foram editadas byte a byte e o `scripts/verifica-testes-arquiteturais.sh`
passa.

**`scripts/smoke.sh` ganhou os passos 10 e 11.** O 10 busca o trace pelo `idVideo` do Vídeo que
**falhou** — de propósito: é o único cujo caminho passa pelos três serviços — e cobra os três
`service.name` num único trace. A busca é ancorada em `resource.service.name = "fiapx-extracao"`,
e isso não é detalhe: como o `idVideo` também marca o span de cada GET de acompanhamento, a busca
só pelo atributo casa dezenas de traces de uma linha, e o primeiro que volta é uma consulta, não a
travessia. Isso reprovou de verdade na primeira execução. O 11 para o container da
observabilidade e prova o ciclo do Vídeo inteiro sem ela, religando-a por um `trap` que roda mesmo
se um passo abaixo falhar.

**Validações, todas contra o Compose de verdade** (imagens construídas localmente, porque o
Compose puxa `latest` do GHCR e o `latest` não tinha o código):

- `./mvnw test` a partir da raiz, **430 testes, sem stack de observabilidade de pé**. Dois testes
  de unidade construíam os beans à mão e passaram a receber um `Tracer`/`Meter` no-op
  (`OpenTelemetry.noop()`), o que motivou trocar a injeção do `Rastro` de campo para construtor.
- `scripts/smoke.sh` completo, onze passos, verde.
- **Rastro**: os três `service.name` num único trace, buscando pelo `idVideo`.
- **Log**: as linhas dos três serviços chegam ao Loki com `idVideo` como campo estruturado **e**
  `trace_id` igual ao do trace que o passo 10 achou — os logs pendurados nos spans certos.
- **Métrica**: `fiapx_extracao_duracao_seconds_count` e `_sum` no Prometheus, com as duas séries
  de `resultado`, uma por réplica do `extracao`.

**O custo da observabilidade, que o 058 deixou por medir.** Fixture de controle, ciclo completo
`POST /videos` → `CONCLUIDO`, JVMs quentes, seis medições de cada lado no mesmo host:

| | Ciclo do Vídeo | RAM dos três serviços |
|---|---|---|
| Tudo ligado, exportando para a stack | 0,56 – 0,59 s | 1.014 MiB |
| `QUARKUS_OTEL_SDK_DISABLED=true` | 0,53 – 0,56 s | 852 MiB |

**Cerca de 30 ms num ciclo de 550 ms — ~5% — e ~160 MiB somando os três serviços** (quatro
JVMs, contando as duas réplicas do `extracao`). A amostragem é 100%, como o ticket exige, e este
é o preço dela no fixture de controle. O número de RAM é aproximado: as JVMs foram medidas com
histórias de uso parecidas, mas não idênticas.

> **Reetiquetado pelo [ticket 062](062-a-chave-que-nao-desliga-o-sdk.md).** As duas linhas acima
> diziam "Com instrumentação" e "Sem instrumentação", e não era isso que elas comparavam.
> `QUARKUS_OTEL_SDK_DISABLED=true` desliga métrica e log de verdade, mas **não** desliga o
> trace: o span continua sendo gravado dos dois lados. O delta destas medições é, portanto,
> *exportar os três sinais + gravar métrica + espelhar log*, e **não** inclui o custo de gravar
> span. O número não foi remedido — medir "com instrumentação" contra "sem" exigiria uma segunda
> leva de imagens, recusada no [ADR 0004](../../adr/0004-camada-de-observabilidade.md).

**Um defeito medido, e não corrigido de propósito: [ticket 061](061-travamento-raro-com-o-sdk-desligado.md).**
Ao medir o lado "sem instrumentação" apareceu um travamento raro: com
`QUARKUS_OTEL_SDK_DISABLED=true`, uma Extração ocasionalmente para no meio — Vídeo em
`PROCESSANDO`, mensagem *unacked*, **nenhuma thread trabalhando e nenhuma linha de log** —, e a
réplica fica presa para sempre por causa do `max-outstanding-messages=1`. Num A/B de três rodadas
alternadas de dez ciclos, logo após `--force-recreate`: **1 travamento em 30 ciclos com as imagens
do 059, 0 em 30 com as imagens pré-059**; fora do A/B, mais três com o SDK desligado e nenhum com
ele **ligado**, que é a configuração da demo. O sinal é real e é meu, mas não tenho causa raiz — o
único trecho que difere com o SDK desligado é o guarda por `isRecording()` do `Rastro`, e o
caminho que ele toma é, em tese, o mesmo de antes deste ticket. Segue no 061 em vez de virar um
patch às cegas, pelo mesmo critério do ticket 027: defeito medido vira ticket, não conserto por
palpite. **A configuração entregue — a demo, com o SDK ligado — não exibiu o sintoma em nenhuma
execução.**

**O que a revisão mudou.** Quatro correções, todas do revisor e nenhuma cosmética:

- O passo 10 cobria só o Vídeo que **falhou** — largura da travessia, não profundidade. Ganhou
  uma segunda checagem sobre o Vídeo **concluído**, cobrando o span `extracao.frames`, que é o
  `ffmpeg` e o mesmo intervalo que a métrica cronometra. Ela reprovou ao ser escrita, por um
  motivo que virou comentário no script: o span do `ffmpeg` **não** carrega `idVideo` — o adapter
  não conhece o Vídeo, e não deve —, então a consulta precisa de **dois spansets** ligados por
  `&&`, e não de duas condições dentro de um.
- O span do caminho não-gravante nascia e era abandonado sem `end()`. Corrigido nas três cópias.
- `VideosResource` reimplementava o par *atributo de span + campo de MDC* que é justamente o que
  o `Rastro` sabe fazer, tomando dele só a constante. Virou `Rastro.marcar(idVideo)`: a dupla
  passa a ter um dono só, e mudar a chave não quebra a busca do outro lado em silêncio.
- A frase sobre o log do `ffmpeg` afirmava uma correlação que eu **nunca exercitei** — e estava
  errada em mais de um sentido. Está reescrita abaixo como desconhecido.

## O que este ticket não entrega

- **Painel montado no Grafana**: a exploração é pelo *Explore*, como o 058 já registrava.
- **Correlação dos logs emitidos de dentro do pipeline de `ffmpeg`**: *não verificada*. O
  `FfmpegExtracaoDeFramesAdapter` roda em `Infrastructure.getDefaultWorkerPool()`, fora do
  contexto duplicado do Vert.x onde vivem o MDC e o contexto de trace, então o aviso de exit
  code de lá provavelmente sai **sem `idVideo` e sem `trace_id`** — os dois, não só o primeiro.
  Não dá para afirmar: os fixtures do repositório não alcançam aquela linha, porque o arquivo
  inválido morre no `ffprobe`, antes de o `ffmpeg` rodar. Fica registrado como desconhecido em
  vez de como garantia. O que **está** verificado é que as linhas emitidas dentro do escopo do
  consumidor chegam ao Loki com os dois campos, nos três serviços.
- **Causa raiz do [061](061-travamento-raro-com-o-sdk-desligado.md)**, pelo motivo acima.
