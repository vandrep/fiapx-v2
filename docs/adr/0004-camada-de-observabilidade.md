# A camada de observabilidade: onde ela entra, onde ela para

Os três serviços exportam log, métrica e trace, e decidimos que essa camada **para na
fronteira**: instrumentação só em `framework`, `core` sem span, contexto de trace no header e
nunca no corpo da mensagem, e a configuração de medição de carga rodando **sem** ela. As três
decisões são de recusa, não de adoção — o que a camada não faz é o que um leitor futuro
questionaria, e é o que não tem outro lugar onde ser respondido.

Decidido nos tickets [058](../wayfinder/tickets/058-piso-de-observabilidade.md) e
[059](../wayfinder/tickets/059-tres-sinais-nos-tres-servicos.md), registrado no
[060](../wayfinder/tickets/060-registrar-a-camada-de-observabilidade.md). O que motivou a
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

## O overlay de carga desliga a observabilidade

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

O preço, que fica escrito em vez de tácito:

- Os números de escala do projeto descrevem um sistema **sem** observabilidade. O custo dela
  no fixture de controle é ~5% no ciclo do Vídeo e ~160 MiB somando os três serviços
  ([ticket 059](../wayfinder/tickets/059-tres-sinais-nos-tres-servicos.md)); **extrapolar isso
  para o regime de pico é conta que ninguém fez.**
- `QUARKUS_OTEL_SDK_DISABLED=true` **não desliga a instrumentação**, e este parágrafo dizia
  que sim. O [ticket 061](../wayfinder/tickets/061-travamento-raro-com-o-sdk-desligado.md)
  mediu, no Quarkus 3.31.3: com a chave ligada por variável de ambiente ou por propriedade de
  sistema, o span continua sendo um `SdkSpan` que grava, e o `Rastro` continua abrindo escopo e
  MDC. O que ela suprime é a **exportação**. Logo o overlay de carga não roda "sem
  instrumentação": ele roda instrumentado, sem exportador. Os ~5% de custo medidos no ticket
  059 comparam duas configurações que diferem menos do que se supôs, e o que fazer a respeito —
  inclusive se a comparação com os tickets 025–028 ainda se sustenta — está no
  [ticket 062](../wayfinder/tickets/062-a-chave-que-nao-desliga-o-sdk.md).
- O travamento raro que o 061 carregava **não vinha do SDK**: a causa raiz é a tolerância a
  falhas por interceptor nos adapters de I/O, que reagendava a chamada no contexto Vert.x do
  próprio consumidor. Está fechado, e o rótulo "com o SDK desligado" no título daquele ticket
  é o nome de uma correlação que a medição desfez.

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
  de thread porque o `QuarkusContextStorage` guarda o contexto no contexto duplicado do Vert.x,
  o mesmo mecanismo pelo qual o Panache acha a sessão — e é também por isso que o log emitido de
  dentro do pool de worker do `ffmpeg` fica **fora** dele.
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
