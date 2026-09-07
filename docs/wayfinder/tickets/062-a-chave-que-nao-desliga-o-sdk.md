# `quarkus.otel.sdk.disabled` desliga a exportação, não a instrumentação

- id: 062
- label: ready-for-agent
- status: aberto
- assignee:
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
