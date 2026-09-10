# A camada de observabilidade: onde ela entra, onde ela para

Os três serviços exportam log, métrica e trace, e decidimos que essa camada **para na
fronteira**: instrumentação só em `framework`, `core` sem span, contexto de trace no header e
nunca no corpo da mensagem, e a configuração de medição de carga rodando **sem coletor**. As três
decisões são de recusa, não de adoção — o que a camada não faz é o que um leitor futuro
questionaria, e é o que não tem outro lugar onde ser respondido.

O [ticket 063](../wayfinder/tickets/063-escopo-do-rastro-atravessa-thread.md) acrescentou uma
quarta seção, e ela não é de recusa: é a regra de onde o escopo do trace pode atravessar thread.
Ela mora aqui porque este documento afirmava o contrário, e porque o defeito que ela remove foi
medido.

A terceira dizia "sem ela" até o [ticket 062](../wayfinder/tickets/062-a-chave-que-nao-desliga-o-sdk.md),
e a diferença de palavra é a diferença entre o que a configuração de carga promete e o que ela
entrega: nada é exportado, mas o span continua sendo gravado dentro do processo.

Decidido nos tickets [058](../wayfinder/tickets/058-piso-de-observabilidade.md) e
[059](../wayfinder/tickets/059-tres-sinais-nos-tres-servicos.md), registrado no
[060](../wayfinder/tickets/060-registrar-a-camada-de-observabilidade.md) e corrigido no
[062](../wayfinder/tickets/062-a-chave-que-nao-desliga-o-sdk.md). O que motivou a
camada é concreto: em 06/09/2026 as duas réplicas do `extracao` subiram com imagem defasada e
morreram em laço de `PRECONDITION_FAILED`; por 14 minutos a fila teve mensagem e **zero
consumidores**, e todo Vídeo enviado ficou em `RECEBIDO`. O dado existia (`docker ps` dizia
`unhealthy`), mas nada o transformava numa pergunta respondível.

O que a camada deliberadamente não **entrega** — sem canal de notificação, retenção efêmera,
imagem de demonstração — está em
[`docs/arquitetura.md` § Limitações conhecidas](../arquitetura.md#limitações-conhecidas): é
limitação, não decisão de desenho.

## O `core` fica sem span

A instrumentação vive **só em `framework`**: borda HTTP, dispatcher de mensagem e adapters de
I/O. Nenhum use case, nenhuma entidade, nenhum controller emite span, e isso é decisão, não
lacuna.

Duas razões, nesta ordem. A primeira é que **os spans de fronteira já contam a história
inteira**: o que o `core` faz entre eles é síncrono, sem I/O e rápido — os 98,2% do tempo de
serviço medidos no [ticket 027](../wayfinder/tickets/027-melhorias-medidas.md) estão dentro do
`ffmpeg`, que é adapter. Um span por use case acrescentaria profundidade ao trace sem
acrescentar resposta a nenhuma pergunta, e um trace mais fundo é mais caro de ler, não menos.
A segunda é a regra de dependência: `@WithSpan` é `io.opentelemetry`, e uma anotação de
biblioteca de infraestrutura dentro do `core` é exatamente o que o
`ArchitectureConstraintsTest` existe para proibir — a mesma proibição que já vale para
JAX-RS, CDI, Hibernate e Mutiny.

**O teste foi endurecido junto com a decisão**, e essa parte importa mais que a decisão. As
listas de import e de anotação proibidos são **nominais e fechadas**: elas só reprovam o que
alguém escreveu nelas. Antes do 059, `io.opentelemetry` e `io.micrometer` não estavam lá, então
um `@WithSpan` num use case passaria em silêncio — a regra estaria mentindo por omissão, que é
pior que não existir. O 059 acrescentou os dois pacotes à lista de import e `WithSpan`,
`SpanAttribute`, `AddingSpanAttributes`, `Counted` e `Timed` à de anotação (a segunda lista
existe porque anotação alcança o código também por import estático ou nome qualificado), nas
três cópias byte a byte.

A consequência de aceitar: um trecho de `core` que um dia fique lento não aparece no trace
por si. Ele aparece como duração inexplicada dentro do span de fronteira que o contém, e a
resposta ali é perfil, não span — o que é o mesmo trade-off que o projeto já faz ao não medir
tempo dentro de método.

## O escopo só atravessa thread preso ao contexto duplicado do Vert.x

O `Rastro` abre um `Scope` do OpenTelemetry para deixar o span corrente durante o trabalho, e
esse trabalho é assíncrono: a cadeia que abre o escopo quase nunca é a que o fecha. Isso é
seguro **exatamente enquanto** o `QuarkusContextStorage` estiver guardando o contexto no
contexto duplicado do Vert.x, porque aí o par abre/fecha é por contexto, não por thread.
Fora dele, o armazenamento é o `MDCEnabledContextStorage`, sobre uma `ThreadLocal`, e a
mecânica muda de figura: o `ThreadLocalContextStorage` só restaura quando o contexto corrente é
o mesmo que ele anexou, então um `close` vindo de outra thread é **ignorado em silêncio** — a
thread que abriu fica com o span, já encerrado, como contexto corrente, e o MDC, que não tem
essa guarda, é reescrito na thread errada.

O [ticket 063](../wayfinder/tickets/063-escopo-do-rastro-atravessa-thread.md) mediu onde isso
acontece, sondando os dez pontos de instrumentação dos três serviços com a suíte inteira. Oito
abrem sobre contexto duplicado. Dois não, os dois no `extracao`: `extracao.frames`, em 4 de 4
Extrações, fechando noutra thread nas 4 — uma delas a `InnocuousThread-1`, do pool comum da JVM
—, e `extracao.gravar-pacote`, em 3 de 3, fechando na mesma thread por acaso. Os dois chegam lá
pelo mesmo motivo: a cadeia segue na thread que completou o download do MinIO, que é do SDK da
AWS. O `videos` não chega porque o `ArquivoMinioAdapter` de lá devolve a continuação ao contexto
de chamada — por causa da sessão do Panache, não por causa do trace.

**A regra que fica é uma:** escopo só atravessa fronteira assíncrona quando está preso ao
contexto duplicado; fora disso, abre e fecha na mesma thread. As duas formas do `Rastro` a
cumprem de jeitos diferentes, e a diferença é o que cada uma precisa:

| | precisa do span corrente durante a espera? | como o escopo se comporta |
|---|---|---|
| `naMensagem` | **sim** — é ele que faz a publicação seguinte ser filha do consumo | atravessa thread, e só quando há contexto duplicado; sem ele, nada de escopo nem MDC, e um `WARN` |
| `emTorno` | **não** — quem lê o contexto corrente é a instrumentação que monta a requisição, no disparo | abre e fecha na mesma thread, em volta do disparo; o span segue vivo até a conclusão |

O sintoma que a correção removeu foi medido, e não deduzido: antes, `extracao.frames` e
`extracao.gravar-pacote` nasciam filhos de `extracao.baixar-video`, um span já encerrado;
depois, os dois nascem filhos de `extracao.extrair-video`. Corrigir o par abre/fecha endireitou
a árvore do trace de tabela. O que reprova se isso regredir é o
`EscopoNaoAtravessaThreadTest`, no `extracao` — uma cópia só, no serviço onde o caminho foi
medido, ao contrário do `Rastro`, que existe em três.

## `idVideo` é a chave que o humano digita; `trace_id` identifica a travessia

O contrato de mensagens define `idVideo` como *"gerado pelo `videos` no upload; correlaciona
todas as mensagens"* desde o [ticket 007](../wayfinder/tickets/007-contrato-mensagens.md), e
ele **não foi substituído** por `trace_id`. Os dois convivem porque respondem perguntas
diferentes:

| | `idVideo` | `trace_id` |
|---|---|---|
| Quem gera | o `videos`, no upload | a infraestrutura, no primeiro span |
| O que identifica | o **Vídeo** — vocabulário do [`CONTEXT.md`](../../CONTEXT.md) | uma **travessia** do sistema |
| Onde vive | corpo das cinco mensagens, linha do Postgres, resposta HTTP | header `traceparent`, nunca no corpo |
| Quantos por Vídeo | exatamente um, para sempre | um por travessia — o envio, cada GET de status, cada reentrega |
| Quem consegue digitá-lo | o usuário, que o recebeu no `Location` do `202` | ninguém: ele não sai do sistema |

A pergunta que a camada precisa responder é *"onde este Vídeo parou"*, e quem a faz tem em
mãos um `idVideo`, não um `trace_id`. Por isso `idVideo` é **atributo de span e campo
estruturado de log em todo serviço que o conhece**: buscar por ele leva ao trace, e é essa
ponte — não o `trace_id` — que torna a camada utilizável por um humano. O `trace_id` faz o
trabalho do outro lado, costurando os saltos entre serviços que nenhum identificador de
domínio conseguiria costurar sozinho, porque um Vídeo tem muitas travessias e elas não se
distinguem por `idVideo`.

Duas consequências que já foram medidas, e não previstas:

- **Buscar só por `idVideo` casa dezenas de traces de uma linha.** Como o atributo marca
  também o span de cada GET de acompanhamento, o primeiro resultado costuma ser uma consulta,
  não a travessia. O passo 10 do `scripts/smoke.sh` ancora a busca em
  `resource.service.name = "fiapx-extracao"` por causa disso, e a checagem reprovou de verdade
  antes de ganhar a âncora.
- **Nem todo span da travessia carrega `idVideo`.** O span do `ffmpeg` não carrega: o adapter
  de extração de frames não conhece o Vídeo, e não deve conhecer. Casar os dois exige **dois
  spansets** ligados por `&&`, e não duas condições dentro de um.

Alternativa recusada: **`trace_id` no corpo das mensagens**. Ela dispensaria o header, mas
mudaria os cinco `record` do contrato para carregar um identificador de infraestrutura dentro
do vocabulário de domínio — e o contrato tem como princípio que a mensagem carrega chaves, não
metadado de transporte. O W3C Trace Context existe exatamente para isso, e viaja no header ao
lado do `x-death`, que é o mesmo tipo de coisa
([`docs/contratos/mensagens.md` § Headers](../contratos/mensagens.md)).

## O overlay de carga mede um sistema instrumentado, sem coletor

`docker-compose.carga.yml` sobe a stack com `replicas: 0` e os serviços com
`QUARKUS_OTEL_SDK_DISABLED=true`. A configuração **medida** é, portanto, diferente da
configuração **entregue**, e isso é deliberado.

O motivo é o método. Os tickets [025](../wayfinder/tickets/025-carga-conservacao.md)–028
mediram conservação sob pico e linearidade horizontal **sem** observabilidade, com critérios
fixados antes de rodar, e a linearidade é limitada por CPU — em N=6 o host já estava a 77% dos
20 núcleos. Um coletor disputando o mesmo host mede outra coisa, e um número de escala colhido
noutra configuração não é comparável com os que já estão registrados. As alternativas eram
piores: **medir com a stack ligada** invalidaria a comparação com tudo que já foi medido;
**varrer N de novo com a stack ligada** custaria as horas de todas aquelas corridas para
responder uma pergunta que ninguém fez. A sobrecarga foi registrada **uma vez**, sobre o
fixture de controle, em vez de varrida.

`replicas: 0` e não `profiles` porque a garantia precisa valer para a corrida, não para a
intenção: com réplicas zero, uma stack já de pé é reduzida a zero pelo `up` do overlay; com
profile, ela continuaria rodando ao lado da medição.

### A chave desliga métrica e log, e não desliga trace

O [ticket 061](../wayfinder/tickets/061-travamento-raro-com-o-sdk-desligado.md) mediu que
`QUARKUS_OTEL_SDK_DISABLED=true` **não impede o span de gravar**, e o
[062](../wayfinder/tickets/062-a-chave-que-nao-desliga-o-sdk.md) foi ler por quê. O mecanismo é
mais estreito do que "a chave suprime a exportação", e a forma exata é o que sustenta a decisão
abaixo.

Quando `sdkDisabled()` é verdadeiro, o `OpenTelemetryRecorder` monta o
`AutoConfiguredOpenTelemetrySdk` **sem** os customizadores do Quarkus, e o autoconfigure do OTel
pula o `configureSdk` inteiro. Sobra um `OpenTelemetrySdk` construído com os três providers no
default — e os três defaults **não são iguais**:

| Provider sem nada configurado | O que ele devolve | Efeito |
|---|---|---|
| `SdkMeterProvider` sem reader | o meter no-op (`ExtendedDefaultMeter`) | métrica realmente desligada |
| `SdkLoggerProvider` sem processor | `loggerBuilder` devolve o logger no-op | log realmente desligado |
| `SdkTracerProvider` sem processor | um tracer de verdade | **span grava** |

O tracer é o único dos três que não tem o atalho "sem processador, vira no-op": quem decide se o
span grava é o **sampler**, e o default (`parentbased_always_on`) amostra. Medido no SDK 1.57.0,
que é o do Quarkus 3.31.3: `SdkTracerProvider.builder().build()` devolve span com
`isRecording() == true`; trocado o sampler por `always_off`, `false`.

Logo o overlay hoje roda **sem métrica, sem log e sem exportador, mas com trace gravando** —
incluindo o escopo e o MDC que o `Rastro` abre, e os spans que a auto-instrumentação monta em
cada salto. Nada sai do processo; tudo é construído dentro dele.

### A decisão: o overlay fica como está, e passa a dizer o que mede

Das três saídas que o 062 pôs na mesa — deixar como está, ganhar uma forma de desligar a
instrumentação de verdade, ou declarar que mede *com* instrumentação —, vale a terceira. Não por
preferência: **não existe caminho suportado para "não instrumente" que um overlay de Compose
alcance nesta versão.** Os três candidatos foram verificados, e cada um caiu por um motivo
diferente:

- **`quarkus.otel.enabled=false`** é `BUILD_AND_RUN_TIME_FIXED`, então variável de ambiente não
  o alcança — e, pior, ele **não compila aqui**: sem os build steps do OTel somem os beans
  `Tracer` e `Meter`, e o `Rastro` e o `DuracaoDaExtracao` reprovam com
  `UnsatisfiedResolutionException`. Medido: `./mvnw -pl extracao package -Dquarkus.otel.enabled=false`
  falha no `ArcProcessor#validate`.
- **`otel.sdk.disabled` como propriedade do autoconfigure** não acrescenta nada, e a razão que
  decide é anterior à do ticket: o desvio do recorder acontece **antes** de o autoconfigure
  existir, no `if (sdkDisabled())` — quem lê essa chave é o `OTelRuntimeConfig` do Quarkus, e o
  `QUARKUS_OTEL_SDK_DISABLED` do overlay já a alcança porque ela é propriedade **declarada** da
  config mapping. É a configuração que já está de pé, com outro nome. (A ressalva do ticket
  sobre `getPropertyNames()` continua de pé para uma variável `OTEL_SDK_DISABLED` crua, que não
  tem chave pontuada declarada — mas ela é irrelevante aqui, porque nem chegaria a ser lida.)
- **Sampler `always_off`** é a única coisa que faria o span parar de gravar, e esbarra em
  **dois** bloqueios independentes. O primeiro: com `sdkDisabled()` verdadeiro o `SamplerCustomizer`
  do Quarkus não roda e o `configureSdk` é pulado, então o sampler configurado **não é consultado
  em configuração nenhuma do overlay** — o span grava pelo sampler *default* do SDK, e não pelo
  `always_on` do `application.properties`. Ligar o SDK de volta só para poder desligar o sampler
  devolveria o exportador, que é o custo que o overlay existe para tirar. O segundo, medido:
  `quarkus.otel.traces.sampler` é fixado no build, e subir o `extracao` empacotado com
  `-Dquarkus.otel.traces.sampler=always_off` produz
  `WARN: Build time property cannot be changed at runtime: quarkus.otel.traces.sampler is set to
  'always_off' but it is build time fixed to 'always_on'` — a corrida usa `always_on`.

Sobra a única forma que funciona, e ela é o que se recusa: **uma segunda leva de imagens**,
construída com `-Dquarkus.otel.traces.sampler=always_off` e os três `*.exporter=none` (isso
compila, foi verificado). Ela custa um artefato paralelo aos três da demo, construído só para o
experimento, que ninguém publica e ninguém roda em produção — e ainda assim **não devolveria a
comparabilidade** com os tickets 025–028, porque aquelas corridas rodaram sobre um código que
desde então mudou por outros motivos (o 061 trocou a tolerância a falhas dos adapters de I/O).
Pagar uma imagem permanente por uma comparação que continua quebrada é troca ruim.

Note que `quarkus.otel.metrics.enabled=false` tampouco serviria como meio-termo: ele também
remove o bean `Meter` e derruba o build do `extracao`, pela mesma
`UnsatisfiedResolutionException`. O que existe é `*.exporter=none`, que preserva os beans.

### O preço, que fica escrito em vez de tácito

- **O overlay mede um sistema instrumentado sem coletor**, e não "código que não instrumenta
  nada". É esta frase que o cabeçalho do `docker-compose.carga.yml` passa a carregar.
- **Os números de escala do projeto — 15,6 Vídeo/min, eficiência 0,88 e 0,99, mediana do 202, 0
  presos em 400 — foram medidos noutra coisa:** as imagens pré-059, que não instrumentavam. Uma
  corrida futura do overlay é comparável com **outra corrida do overlay**, não com aqueles
  quatro números. A diferença entre as duas configurações é o custo de gravar span sem exportar,
  e ele **não está medido**.
- **O número do 059 foi reetiquetado, não remedido.** Os ~5% no ciclo do Vídeo e os ~160 MiB
  somando os três serviços comparam duas configurações que a tabela acima descreve com precisão:
  de um lado tudo ligado; do outro, métrica e log **realmente desligados** e o trace gravando sem
  sair. O delta é, então, **exportar os três sinais mais gravar métrica e espelhar log** — e o
  que ele **não** contém é a gravação de span, que os dois lados pagam igual. Não é "o custo da
  instrumentação", que é como o 059 e o mapa o rotulam. Remedir "com instrumentação" contra
  "sem" exigiria justamente a segunda leva de imagens recusada acima, e responderia uma pergunta
  que nenhum requisito faz. Extrapolar qualquer um dos dois para o regime de pico continua sendo
  conta que ninguém fez.
- **O guarda por `isRecording()` do `Rastro` não dispara em nenhuma configuração deste
  repositório**, e isso segue sendo verdade depois desta decisão — inclusive em `%test` e `%dev`.
  O `SdkDesligadoAindaGravaTest` existe para que a afirmação não envelheça em silêncio: se um
  upgrade do Quarkus fizer a chave desligar de verdade, ele reprova, e aí esta seção precisa ser
  reescrita. Ele vive **só no `extracao`**, e nos outros dois a afirmação vale por analogia —
  os três têm o mesmo bloco de configuração e a mesma extensão, e um teste por serviço para uma
  propriedade da extensão pagaria três cópias pelo mesmo sinal.
- **O travamento raro que o 061 carregava não vinha do SDK**: a causa raiz é a tolerância a
  falhas por interceptor nos adapters de I/O, que reagendava a chamada no contexto Vert.x do
  próprio consumidor. Está fechado, e o rótulo "com o SDK desligado" no título daquele ticket
  é o nome de uma correlação que a medição desfez.

## Os três dashboards de fábrica, e o que cada um responde

A imagem `grafana/otel-lgtm` **provisiona três dashboards sozinha**, e este documento os ignorou
até o [ticket 091](../wayfinder/tickets/091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md)
— a camada foi registrada como se a exploração ad-hoc no *Explore* fosse a única superfície de
leitura. Não era: havia três telas na home do Grafana, e as três respondiam *"No data"* sobre um
sistema saudável.

| Dashboard da imagem | Serve? | Por quê |
|---|---|---|
| *RED Metrics (classic histogram)* | **sim** | taxa e duração do HTTP dos três serviços |
| *JVM Overview (OpenTelemetry)* | **sim** | heap, threads, classes e GC, uma série por container |
| *RED Metrics (native histogram)* | **não** | consulta histograma nativo; o Quarkus exporta clássico |

O painel de **erro** do primeiro é o único que continua podendo aparecer vazio, e isso não é
defeito: ele conta `http_response_status_code=~"5.."`, e num ciclo saudável não há nenhum. As
demais séries dos dois dashboards que servem foram conferidas com as variáveis em "All" — GC
inclusive, que tem `jvm_gc_duration_seconds_sum` por container.

Os dois que servem passaram a servir porque as séries ganharam `instance`. O mecanismo é o
oposto do intuitivo: quem traduz OTLP→Prometheus **não é o coletor, é o próprio Prometheus**, no
`/api/v1/otlp`, e ele mapeia `service.name` → `job` e `service.instance.id` → `instance`. O
primeiro chega; o segundo não era emitido por ninguém, e as três telas filtram toda query por
`instance=~"$instance"` com `allValue: ".+"` — um matcher que **exige a etiqueta existir**. As
únicas séries que a tinham eram as de *scrape* (`rabbitmq` e `otelcol-contrib`), que a setam
nativamente. O conserto é um processador `transform` na pipeline de métrica do
`docker/observabilidade/otelcol-config.yaml`, copiando `host.name` — que já está em toda série e
é o id do container, portanto único por réplica — para `service.instance.id`, com uma guarda
`== nil` que preserva quem já traz a sua.

O terceiro **continua morto, e é estrutural**: ele consulta
`http_server_request_duration_seconds` como histograma nativo, sem sufixo, e o Quarkus exporta
clássico (`_bucket`/`_count`/`_sum`). Nenhuma etiqueta conserta isso; só ligar histograma
exponencial no exportador dos três serviços, para atender um dashboard que ninguém pediu. Fica
registrado como fato conhecido, não como pendência.

Isto **não reabre** a recusa de painel curado abaixo — é o argumento dela levado a sério. Dois
dashboards mantidos pela imagem respondem HTTP e JVM a custo zero de manutenção, que é
exatamente o que aquela recusa prefere a um painel nosso. O que o ticket 091 corrigiu foi a
camada estar entregando esse ganho **desligado**, e o custo disso ser maior que o de não tê-lo:
uma tela que diz "não há dados" quando há é pior que uma tela que não existe.

## Considered Options

**Não instrumentar, e continuar registrando a ausência como limitação** era a posição até
06/09/2026, e ela caiu por dois motivos independentes. O primeiro é que a premissa acabou: a
recusa dizia *"canibalizaria o tempo do CI/CD"*, e o CI/CD estava entregue. O segundo é que a
própria seção *Limitações conhecidas* já nomeava esta lacuna — *"a primeira coisa que eu
acrescentaria com mais tempo seria visibilidade sobre profundidade de fila e taxa de
dead-letter"* — e o incidente de 06/09 mostrou o custo dela em minutos de diagnóstico, não em
argumento.

**Painel curado no Grafana** foi recusado e continua fora: a exploração ad-hoc no *Explore*
responde as mesmas perguntas sem manutenção, e um painel é a parte que envelhece primeiro. Na
demo ele seria pior ainda — um painel vazio prova menos que uma busca por `idVideo` que
devolve os três serviços.
*Revertido em parte pelo [ticket 092](../wayfinder/tickets/092-painel-do-vao-e-a-reversao-parcial-da-recusa.md)
— ver § Um painel, e o que da recusa continua de pé, no fim deste documento.*

**Canal de notificação de alerta** foi adiado, não recusado por mérito: é configuração de
*contact point*, e sem ele **a detecção não mudou**. Está escrito como limitação, e não como
trabalho futuro, porque a diferença importa para quem lê o documento decidindo se pode confiar
no sistema.

**Cinco containers em vez de um** foi recusado por medição: `grafana/otel-lgtm` custa 365 MiB
de RAM e sobe em 12 s, bem menos que a stack montada peça a peça, e a demo precisa rodar na
máquina de quem avalia. O preço aceito é a imagem de 3,6 GB e o fato de a imagem ser de
demonstração.

**Amostragem abaixo de 100%** foi recusada porque destruiria justamente o caso raro que motiva
a camada — o Vídeo que parou —, e porque o volume da demo é trivial: o pico de 400 Vídeos do
overlay de carga nem chega à stack, já que o overlay a desliga.

## Consequences

- **A auto-instrumentação para em três lugares, e é onde a instrumentação própria entra.**
  Medido, não suposto: o conector RabbitMQ encerra o span de recebimento **antes** de o método
  `@Incoming` rodar (`TracingUtils.traceIncoming` chama `instrumenter.end` e fecha o escopo),
  então sem um span nosso o span de recebimento teria duração ~0 e a publicação seguinte
  viraria **raiz nova** — o rastro se partiria em cada salto entre serviços. Os outros dois são
  o `ffmpeg`, que roda fora do JVM, e o MinIO, cujo `AwsSdkTelemetry` monta sozinho mas **não
  emitiu span nenhum** quando conferido no Tempo. O terceiro chegou a reverter uma decisão no
  meio do 059: os spans próprios de S3 foram removidos por causa da regra de nomes e devolvidos
  depois da medição.
- **Os quatro consumidores recebem `Message`, não payload.** O contexto de trace chega no
  header AMQP e só é alcançável pelo `Message`; daí `@Acknowledgment(MANUAL)` explícito e
  ack/nack à mão. O comportamento observável não mudou, e o nack continua caindo no
  `failure-strategy` de cada canal.
- **A costura é uma classe por serviço, `framework/observabilidade/Rastro.java`.** Não há módulo
  compartilhado ([ticket 007](../wayfinder/tickets/007-contrato-mensagens.md)), então são três
  cópias, com a mesma disciplina das três cópias do teste arquitetural. Ela sobrevive aos saltos
  de thread **quando** há contexto duplicado do Vert.x, que é onde o `QuarkusContextStorage`
  guarda o contexto — o mesmo mecanismo pelo qual o Panache acha a sessão, e também o motivo de
  o log emitido de dentro do pool de worker do `ffmpeg` ficar **fora** dele. Nem sempre há: o
  [ticket 063](../wayfinder/tickets/063-escopo-do-rastro-atravessa-thread.md) mediu dois pontos
  do `extracao` que rodam sem contexto duplicado nenhum, e a regra que saiu daí está em
  *O escopo só atravessa thread preso ao contexto duplicado do Vert.x*, acima.
- **Uma métrica própria, e só uma.** `fiapx.extracao.duracao`, em segundos, com o atributo
  `resultado` (`concluida`/`falhou`). Ela existe porque é o único intervalo que nenhuma
  auto-instrumentação enxerga — processo externo —, e o atributo não a torna duas métricas: sem
  ele, uma Extração que morre no teto de 300 s entraria na mesma distribuição das que
  terminaram, e a mediana mediria a mistura de duas populações. Vídeos-por-estado ficou de fora:
  o endpoint de listagem já responde, e um gauge exigiria varredura periódica no banco.
- **Nomes têm duas origens e duas regras.** O que a auto-instrumentação emite fica **como o
  OTel emite** — é contrato com a ferramenta, e traduzir quebra o ecossistema. O que é nosso usa
  o vocabulário do [`CONTEXT.md`](../../CONTEXT.md): `Extração`, `concluida`, `falhou`,
  `idVideo`. A regra está no [`AGENTS.md`](../../AGENTS.md).
- **O `CONTEXT.md` não mudou, e isso é decisão.** Os alertas se apoiam em termos que o glossário
  já define — Estacionamento, tentativas esgotadas, Vídeo preso —, e trace, span e travessia são
  vocabulário de infraestrutura, não de domínio. O glossário é glossário; termo de ferramenta
  entrando nele o transformaria noutra coisa.
- **A stack fica fora do caminho de boot, e isso é verificado.** Nenhum `depends_on` dos três
  serviços aponta para ela, e o passo 11 do `scripts/smoke.sh` derruba o container e prova que o
  ciclo do Vídeo completa mesmo assim, religando-o por um `trap`. O console também não mudou:
  `docker logs` foi o que diagnosticou o incidente de 06/09, e o cenário em que ele mais importa
  é justamente aquele em que a stack de observabilidade é o que está quebrado.

## Um painel, e o que da recusa continua de pé

A recusa de painel curado acima é **revertida em parte** pelo
[ticket 092](../wayfinder/tickets/092-painel-do-vao-e-a-reversao-parcial-da-recusa.md): existe
**um** painel, `docker/observabilidade/painel-infraestrutura.json`, provisionado por arquivo e
home do Grafana. Um, e não uma suíte. O parágrafo recusado fica onde está: painel curado esteve
fora, e por que esteve é parte do registro.

**Cai o argumento do Explore, e cai por quem é o leitor.** *"A exploração ad-hoc responde as
mesmas perguntas"* pressupõe alguém que sabe o que perguntar. O público desta camada não é quem
a escreveu: é o avaliador, nos dez minutos do vídeo, e ele não tem como saber que existe uma
fila chamada `extracao.extrair.estacionamento` — não há consulta que ele possa formular no
Explore. A diferença que o painel faz não é de eficiência; é entre *"está tudo verde"* e *"não
sei o que perguntar"*.

**Fica de pé o argumento do envelhecimento**, e por isso ele virou requisito em vez de ser
dispensado. O passo 12 do `scripts/smoke.sh` lê as queries **do arquivo do painel** e reprova a
que devolver série vazia num sistema que acabou de processar um Vídeo. É a mesma disciplina das
três cópias do teste arquitetural e do `SdkDesligadoAindaGravaTest`: o que pode mentir em
silêncio ganha quem o cobre. O passo roda **depois** do ciclo do Vídeo, e não antes, porque
`fiapx.extracao.duracao` está legitimamente vazia até a primeira Extração — medido numa stack
recém-subida: 88 nomes de métrica na base, zero com `durac`.

**Fica de pé, e é o que decide o escopo, o "painel vazio prova menos".** O painel cobre só o
**vão** — fila, Estacionamento, DLQ, consumidores e `fiapx.extracao.duracao` —, que é o que
nenhum dashboard de fábrica olha. HTTP e JVM ficam de fora: são dos dois dashboards que a
imagem mantém e que o [ticket 091](../wayfinder/tickets/091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md)
fez enxergar os três serviços, e o painel apenas linka para eles. Repetir aqui série que outro
dashboard já mantém seria exatamente o envelhecimento que a recusa temia, com o agravante de
que o dono do outro dashboard é a imagem, que muda sozinha no upgrade. Continua fora, e pelo
motivo já registrado acima, **contagem de Vídeo por estado**: o endpoint de listagem responde, e
um gauge exigiria varredura periódica no banco.

Três decisões de forma que o arquivo carrega, e o porquê de cada uma:

- **As expressões de fila são derivadas das dos três alertas** de
  `docker/observabilidade/alertas.yaml`, e não reinventadas. Duas verdades sobre a mesma
  pergunta é como o painel começa a divergir do que alerta.
- **A busca de trace herda a âncora do passo 10 do smoke**: `resource.service.name =
  "fiapx-extracao"`, em **dois spansets ligados por `&&`**. Os dois motivos estão medidos em
  *`idVideo` é a chave que o humano digita*, acima — sem a âncora a busca casa dezenas de traces
  de uma linha, e num spanset só ela não casaria nada, porque o span do `ffmpeg` não carrega
  `idVideo`.
- **A duração aparece como média por `resultado`, e não como quantil.** Medido: os limites de
  bucket são os default do OpenTelemetry (0, 5, 10, 25 … 10000), pensados para milissegundos, e
  a Extração do fixture leva ~0,19 s — toda observação cai no primeiro bucket, então
  `histogram_quantile` devolveria interpolação, não medida. E um quantil sobre `rate()` devolve
  `NaN` no volume da demo: a série nasce já com a contagem, sem incremento dentro da janela. A
  soma e a contagem são exatas nos dois casos, e o corte por `resultado` — que é o ponto da
  métrica — sobrevive inteiro.
