# `quarkus.otel.sdk.disabled` cala a exportação, e não desliga o trace

- id: 062
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## O achado

Medido durante o [ticket 061](061-travamento-raro-com-o-sdk-desligado.md), no Quarkus 3.31.3,
perfil `prod`, imagem do `extracao`:

- com `QUARKUS_OTEL_SDK_DISABLED=true` (variável de ambiente, como o
  `docker-compose.carga.yml` usa), uma sonda dentro do `Rastro` registrou
  `span.isRecording() == true`, e o contexto duplicado do Vert.x carregava um `SdkSpan` com
  `traceId`, `spanId`, `sampled=true` e atributos — mais o `idVideo` no MDC, que **só** o
  caminho gravante do `Rastro` escreve;
- o mesmo com `-Dquarkus.otel.sdk.disabled=true` passado por `JAVA_TOOL_OPTIONS`, isto é,
  como propriedade de sistema de verdade e não só como variável de ambiente;
- o Tempo, nas duas configurações, ficou vazio.

Ou seja: a chave suprime a **exportação**, e não a instrumentação. Bate com o
`OpenTelemetryRecorder` do `quarkus-opentelemetry`: quando `sdkDisabled()` é verdadeiro ele
monta o `AutoConfiguredOpenTelemetrySdk` **sem** os customizadores que instalam os
exportadores, mas monta um SDK, e o SDK grava.

## Por que isso importa

Três coisas no repositório se apoiavam na leitura contrária. As duas primeiras já foram
corrigidas pelo 061; a terceira é o que este ticket precisa decidir.

1. **O javadoc do `Rastro` e o comentário do `application.properties`** afirmavam que com o SDK
   desligado o span nasce sem gravar e o guarda por `isRecording()` devolve a cadeia crua.
   Corrigido nos três serviços. O guarda continua no código — ele é correto para um `Tracer`
   no-op de verdade —, mas passa a ser documentado como o que é: um caminho que nenhuma
   configuração deste repositório exerce.
2. **O ticket 061 descartou a hipótese certa por causa dessa frase.** Corrigido, e registrado
   lá.
3. **O `docker-compose.carga.yml` diz que preserva o método dos tickets 025–028** — que
   mediram código *sem instrumentação nenhuma* — desligando o SDK. Ele não preserva: o overlay
   roda instrumentado, sem exportador. O comentário do arquivo, o ADR 0004 e o
   `docs/arquitetura.md` já trazem a ressalva; a decisão em si não foi tomada.

## O que decidir

- O overlay de carga deve continuar como está (instrumentado, sem exportador), ganhar uma
  forma de desligar a instrumentação de verdade, ou passar a declarar que mede *com*
  instrumentação? Cada opção tem um preço diferente sobre a comparabilidade com os números
  já registrados.
- Existe caminho suportado para "não instrumente" nesta versão? Candidatos a verificar, não a
  supor: `quarkus.otel.enabled=false` (build time), `otel.sdk.disabled` como propriedade
  reconhecida pelo autoconfigure (o `OpenTelemetryRecorder` só repassa `quarkus.otel.*` que
  apareçam em `getPropertyNames()`, e uma variável de ambiente sem chave pontuada
  correspondente não aparece), ou um `SdkTracerProvider` com sampler `always_off`.
- Os ~5% de custo do ciclo do Vídeo e os ~160 MiB medidos no ticket 059 comparam
  "com exportador" contra "sem exportador", e não "com instrumentação" contra "sem". Vale
  remedir sobre o fixture de controle, ou vale só reetiquetar o número?

## O que entregar

A decisão registrada onde ela mora — ADR 0004 e o cabeçalho do `docker-compose.carga.yml` —, e
o que ela implicar em configuração. Se a conclusão for "fica como está", ela precisa estar
escrita com a mesma clareza: o overlay mede um sistema instrumentado sem coletor, e os números
de escala do projeto foram medidos noutra coisa.

## Dependências

Nenhuma. O 061 está fechado e carrega a medição que originou este ticket.

## Resolução

**O overlay de carga fica como está, e passa a declarar que mede um sistema instrumentado sem
coletor.** É a terceira das três saídas listadas acima, e ela foi escolhida porque as outras duas
não existem: não há caminho suportado, no Quarkus 3.31.3, para um overlay de Compose desligar a
instrumentação.

### O mecanismo, mais estreito do que o 061 concluiu

O 061 registrou "a chave suprime a exportação, e não a instrumentação". Lendo o
`OpenTelemetryRecorder` e o SDK 1.57.0, o recorte certo é outro, e ele é melhor:
`quarkus.otel.sdk.disabled=true` **desliga métrica e log de verdade, e falha só no trace**.

Com `sdkDisabled()` verdadeiro o recorder monta o `AutoConfiguredOpenTelemetrySdk` sem os
customizadores, e o autoconfigure pula o `configureSdk` inteiro (`otel.sdk.disabled` chega lá
traduzido pelo próprio recorder, que corta `quarkus.` do nome). Sobram os três providers no
default, e os defaults divergem:

| Provider sem nada configurado | O que devolve | Efeito |
|---|---|---|
| `SdkMeterProvider` sem reader | meter no-op (`ExtendedDefaultMeter`) | métrica desligada |
| `SdkLoggerProvider` sem processor | `loggerBuilder` devolve o logger no-op | log desligado |
| `SdkTracerProvider` sem processor | um tracer de verdade | **span grava** |

O tracer é o único sem o atalho "sem processador, vira no-op": quem decide é o sampler, e o
default amostra. Medido no SDK 1.57.0: `SdkTracerProvider.builder().build()` devolve span com
`isRecording() == true`; trocando o sampler por `always_off`, `false`.

### Os três candidatos, verificados

Nenhum deles alcança um overlay de Compose, que só sabe passar variável de ambiente:

| Candidato | Verdicto | Como foi verificado |
|---|---|---|
| `quarkus.otel.enabled=false` | fixado no build, **e não compila aqui** | `./mvnw -pl extracao package -DskipTests -Dquarkus.otel.enabled=false` reprova no `ArcProcessor#validate`: sem os build steps do OTel somem os beans `Tracer` e `Meter`, e o `Rastro` e o `DuracaoDaExtracao` ficam com `UnsatisfiedResolutionException` |
| `otel.sdk.disabled` pelo autoconfigure | não acrescenta nada | o desvio do recorder acontece **antes** de o autoconfigure existir, no `if (sdkDisabled())`, e quem lê a chave é o `OTelRuntimeConfig`. É a configuração que já está de pé, com outro nome |
| sampler `always_off` | **dois** bloqueios | (1) com o SDK desligado o `SamplerCustomizer` não roda e o `configureSdk` é pulado — o sampler configurado não é consultado, e o span grava pelo *default* do SDK; (2) a propriedade é fixada no build: subir o `extracao` empacotado com `-Dquarkus.otel.traces.sampler=always_off` produz `WARN: Build time property cannot be changed at runtime: quarkus.otel.traces.sampler is set to 'always_off' but it is build time fixed to 'always_on'` |

Sobre o candidato 2, a ressalva que este ticket levantou — variável de ambiente sem chave
pontuada correspondente não aparece em `getPropertyNames()` — continua de pé para um
`OTEL_SDK_DISABLED` cru, e é **irrelevante aqui**: `quarkus.otel.sdk.disabled` é propriedade
declarada da config mapping, então o `QUARKUS_OTEL_SDK_DISABLED` do overlay a alcança, e mesmo
que não alcançasse o desvio do recorder acontece antes de o autoconfigure ler qualquer coisa.

As três medições são repetíveis com os comandos acima, mais o do build que **passa**
(`-Dquarkus.otel.traces.sampler=always_off -Dquarkus.otel.traces.exporter=none
-Dquarkus.otel.metrics.exporter=none -Dquarkus.otel.logs.exporter=none`). Elas não deixaram
artefato no repositório de propósito: são fatos sobre a **versão da extensão**, não sobre este
código, e o que precisa reprovar quando o Quarkus mudar já é o `SdkDesligadoAindaGravaTest`.

A única forma que funciona é **uma segunda leva de imagens**, construída com
`-Dquarkus.otel.traces.sampler=always_off` e os três `*.exporter=none` — isso compila, foi
verificado. (`quarkus.otel.metrics.enabled=false` não serve nem como meio-termo: remove o bean
`Meter` e derruba o build do `extracao` pela mesma exceção. `*.exporter=none` preserva os beans.)

Ela está recusada: custa um artefato paralelo aos três da demo, construído só para o
experimento, que ninguém publica e ninguém roda — e **ainda assim não devolveria a
comparabilidade** com os tickets 025–028, porque aquelas corridas rodaram sobre um código que
mudou desde então por outros motivos (o 061 trocou a tolerância a falhas dos adapters de I/O).
Uma imagem permanente por uma comparação que continua quebrada é troca ruim.

### O número do 059: reetiquetado, não remedido

Os ~5% no ciclo do Vídeo e os ~160 MiB comparam *tudo ligado e exportando* contra *métrica e log
realmente desligados, trace gravando sem sair*. O delta é, então, **exportar os três sinais,
gravar métrica e espelhar log** — e o que ele **não** contém é a gravação de span, que os dois
lados pagam igual. "Custo da instrumentação", como o 059 e o mapa rotulavam, é errado nos dois
sentidos: inclui exportação, e exclui a única instrumentação que sobrevive à chave. O rótulo foi
trocado onde o número mora (ticket 059 e `map.md`), com um bloco de citação no 059 dizendo o que
mudou e por quê. Remedir "com instrumentação" contra "sem" exigiria a segunda leva de imagens
recusada acima, e responderia uma pergunta que nenhum requisito faz; o resíduo — o custo de
gravar span sem exportar — fica declarado como **não medido**, que é mais honesto que um número
colhido noutro código.

A consequência que sobra escrita: uma corrida futura do overlay é comparável com **outra corrida
do overlay**, e não com os quatro números de escala do mapa.

### Onde a decisão ficou registrada

- [ADR 0004](../../adr/0004-camada-de-observabilidade.md): a seção do overlay foi reescrita
  (título incluído — ela se chamava "O overlay de carga desliga a observabilidade"), com o
  mecanismo, os três candidatos e o preço; o cabeçalho do ADR trocou "sem ela" por "sem coletor".
- `docker-compose.carga.yml`: o bloco do SDK diz o que o overlay mede, e por que fica assim.
- `docs/arquitetura.md` § *Limitações conhecidas*: a limitação agora separa "configuração medida
  ≠ configuração entregue" de "configuração medida ≠ configuração dos números de escala".
- [Ticket 059](059-tres-sinais-nos-tres-servicos.md) e `docs/wayfinder/map.md`: o rótulo do
  número, trocado onde ele mora — é a reetiquetagem, e ela não valeria nada só no ADR.
- Os três `Rastro.java` e os três `application.properties`: o recorte estreito em uma frase e o
  ponteiro para o ADR, que é onde o mecanismo mora. O comentário do sampler dizia que o overlay
  "desliga o SDK dos tres servicos" — agora diz que ele cala os exportadores, sem afirmar nada
  sobre o sampler, que nessa configuração nem chega a ser consultado.
- `SdkDesligadoAindaGravaTest`: o javadoc deixou de anunciar uma decisão pendente e passou a
  dizer que a decisão do 062 se apoia neste comportamento. Se ele reprovar num upgrade, é o 062
  que reabre. Ele vive **só no `extracao`**, e nos outros dois a afirmação vale por analogia: os
  três têm o mesmo bloco de configuração e a mesma extensão, e três cópias pagariam três vezes
  pelo mesmo sinal.

**Nada mudou em configuração**, e isso é a decisão, não uma omissão: `QUARKUS_OTEL_SDK_DISABLED`
continua no overlay, porque cala mesmo os três exportadores — e, agora que se sabe, desliga
métrica e log de fato.

### Validação

- `./mvnw test` da raiz.
- As medições deste ticket são as três da tabela de candidatos, todas repetíveis com o
  `extracao` empacotado.
