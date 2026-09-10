# Os limites de bucket de `fiapx.extracao.duracao` são de milissegundos, e o quantil é `NaN`

- id: 094
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-10)
- bloqueado-por:
- prioridade: P3

## Origem

Medido no [092](092-painel-do-vao-e-a-reversao-parcial-da-recusa.md), ao montar o painel, e
registrado lá como fora de escopo: *"mudar os limites de bucket seria código novo no `extracao`, e
outro ticket"*. Este é o ticket. Reaberto como item próprio na sessão de 2026-09-10, ao desenhar o
[093](093-trafego-sintetico-para-alimentar-os-paineis.md) — o 093 espalha a duração misturando
fixtures, o que torna o defeito **mais** visível, não menos.

## O problema, como foi medido

`fiapx.extracao.duracao` é um histograma sem `explicit_bucket_boundaries` configurado, então herda
os *default* do OpenTelemetry — `0, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000,
7500, 10000` —, que são pensados para **milissegundos**. A métrica é gravada em **segundos**.

Consequência medida contra a stack, no 092: a Extração do fixture de controle leva **0,19 s**
(concluída) e **0,048 s** (falha no ffprobe). Todas as observações caem no primeiro bucket, e
`histogram_quantile` ali é interpolação linear dentro de `[0, 5]` — não é medida. A expressão de
manual (`histogram_quantile` sobre `rate(..._bucket[$__rate_interval])`) devolveu `NaN` em **todas**
as amostras de uma janela de uma hora.

O painel do 092 contornou com `_sum / _count` por `resultado`, que é exato e preserva o corte que é
o ponto da métrica. O contorno continua correto e **não** é o que este ticket desfaz: o que falta é
poder responder *"qual é a cauda?"*, que média não responde e que é a pergunta para a qual um
histograma existe.

## O que precisa ser decidido, e não está

- **Os limites.** A Extração vai de ~0,05 s (falha no ffprobe) a 300 s (o teto). Uma escala
  candidata é `0,1 / 0,25 / 0,5 / 1 / 2,5 / 5 / 10 / 30 / 60 / 120 / 300`, e ela precisa ser
  justificada contra duração medida, não escolhida por elegância — o 093 é o instrumento que
  produz essa distribuição.
- **Onde se configura.** Por `application.properties`
  (`quarkus.otel.metrics.histogram.*` / view do SDK) ou no próprio
  instrumento. A instrumentação vive **só em `framework`** e o `ArchitectureConstraintsTest` cobra
  isso ([ADR 0004](../../adr/0004-camada-de-observabilidade.md)).
- **Se o painel volta ao quantil.** Se volta, o passo 12 do `smoke.sh` precisa voltar a contar
  amostras não-`NaN` sobre a nova expressão — foi essa contagem que expôs o problema original, e
  ela é a guarda que impede o painel de mentir de novo.

## Por que não viajou junto com o 093

Código de produção no `extracao` tem o seu próprio porquê a registrar, e empacotá-lo com um script
de tráfego é o que faz revisão ficar impossível de ler. O 093 não depende deste ticket: ele
espalha a duração de qualquer forma, e a média por `resultado` continua exata.

## Resolução

As três decisões pendentes foram tomadas, e as três estão medidas.

### Os limites

`0,1 · 0,25 · 0,5 · 1 · 2,5 · 5 · 10 · 30 · 60 · 120 · 300 · 420`, em segundos. É a escala
candidata do corpo deste ticket com **um limite a mais no topo**, e o acréscimo é a única
mudança de desenho: `timeout-ffmpeg-segundos` é 300, e o teto de relógio de uma Extração
inteira é 330 s de processo externo mais o I/O que não tem teto próprio. Com 300 no topo, quem
morre no teto cai no `+Inf` junto com quem passou de todo teto conhecido — dois diagnósticos
diferentes somados numa série só, e um `histogram_quantile` que devolve 300 para os dois. O
limite em 420 é o mesmo `dreno-timeout-segundos`, e é o que dá bucket próprio a quem bate no
teto.

Essa separação mora na **série**, e não no quantil, e a revisão desta sessão pegou a diferença:
`histogram_quantile` interpola dentro de `(300, 420]` e devolve 420 cravado quando o quantil cai
no `+Inf`, então a curva mostra a mesma coisa nos dois casos. Quem responde *"bateu no teto ou
passou dele?"* é a contagem dos dois buckets. O painel diz isso, e manda conferir lá.

O piso ficou em `0,1`, e a falha rápida do ffprobe (~0,05 s) cai **inteira** no primeiro
bucket, de propósito: um limite em 0,05 cortaria aquela população no meio da moda dela. O preço
é que quantil de `falhou` no regime rápido é teto — *"menos de 0,1 s"* —, e não medida; o
painel e o javadoc dizem isso com todas as letras. A resolução que interessa do lado do
`falhou` é a de cima, a do teto.

**O critério é a cauda, e não a moda**, porque é só a cauda que faltava responder — o ticket a
nomeia assim: *"o que falta é poder responder 'qual é a cauda?', que média não responde e que é a
pergunta para a qual um histograma existe"*. Duração típica já sai exata do `_sum / _count`, que
não depende de bucket nenhum e continua no painel. E a cauda ficou resolvida: p90, p95 e p99
caíram em buckets distintos na corrida de validação.

A moda ficou grossa, e isso é escolha e não descuido: as 193 concluídas puseram **114 entre
0,25 s e 0,5 s**, então a mediana lida no painel é interpolação dentro daquele bucket. Um limite
em 0,4 partiria o bloco, e estaria ajustado a este host e a esta mistura de fixtures — a moda
anda com o hardware, e quem responde por ela é a média, que não erra quando ela andar. O miolo
(2,5 · 5 · 10) está ocupado pela medição — 3, 19 e 20 observações —, e os três acima dele
(30 · 60 · 120) existem para que o regime de teto tenha resolução: sem eles, entre 10 s e 300 s
não haveria nada, e uma Extração de 45 s leria como um ponto qualquer de uma faixa de quase cinco
minutos.

### Onde se configura

No próprio instrumento, por `setExplicitBucketBoundariesAdvice` no `DuracaoDaExtracao` —
`framework`, como o `ArchitectureConstraintsTest` e o [ADR 0004](../../adr/0004-camada-de-observabilidade.md)
cobram. A advice viaja com o instrumento, então quem lê a classe lê os limites junto do que
eles medem. Uma *view* no `application.properties` teria de nomear a métrica de novo, num
arquivo onde nada mais fala dela, e criaria a segunda verdade de sempre.

### Se o painel volta ao quantil

Volta, como painel **novo**, ao lado da média — e a média fica. Ela é exata, não depende de
bucket nenhum e responde a duração típica; o quantil responde a cauda. As duas leituras
convivem, e nenhuma das duas foi desfeita.

A expressão é `histogram_quantile(0.95, sum by (le, resultado) (fiapx_extracao_duracao_seconds_bucket))`
— sobre o contador **acumulado**, e não sobre `rate()`. A segunda medição do 092 continua de
pé: quantil sobre `rate()` devolve `NaN` no volume da demo, porque numa janela sem Extração
nenhuma todos os buckets rendem zero. O que se lê no painel é, portanto, o p95/p99 **desde que
a réplica subiu**, e não o da janela do gráfico — o que é a mesma convenção dos dois painéis
vizinhos, cuja retenção morre no `down`.

O passo 12 do `smoke.sh` **não precisou de código novo**: ele lê as queries do arquivo do
painel e já contava amostras não-`NaN`. Ganhou o comentário que diz que agora é ele o guarda do
quantil.

### O que mudou

| Arquivo | Mudança |
|---|---|
| `extracao/.../observabilidade/DuracaoDaExtracao.java` | os doze limites, e o javadoc com o porquê de cada faixa e a distribuição medida |
| `extracao/src/test/.../LimitesDeBucketDaDuracaoTest.java` | **novo**. Quatro testes sobre um `SdkMeterProvider` montado à mão |
| `docker/observabilidade/painel-infraestrutura.json` | painel novo de p95/p99 por `resultado`; a descrição do painel de média deixou de afirmar o que o 092 media |
| `scripts/smoke.sh` | comentário no passo 12: a contagem de amostras não-`NaN` é o guarda do quantil |
| `docs/adr/0004-camada-de-observabilidade.md` | a decisão *"média, e não quantil"* virou *"média e quantil"*, com o que de fato mudou e o que continua de pé |

### Como foi verificado

**Pelo teste, primeiro.** `LimitesDeBucketDaDuracaoTest` reprovou nos quatro casos antes da
mudança, com a mensagem certa: os limites eram `0, 5, 10, 25 … 10000`, as três durações medidas
no 092 (0,048 s, 0,19 s, 300 s) caíam em dois buckets em vez de três, e a Extração no teto de
330 s caía no `+Inf`. Ele não passa pelo CDI de propósito — a suíte roda com
`quarkus.otel.sdk.disabled=true` e o meter injetado é no-op, que não tem bucket para conferir.
O `opentelemetry-sdk-testing` não está no classpath e **não foi acrescentado**: o `InMemoryMetricReader`
que ele traria são vinte linhas dentro do próprio teste.

`./mvnw test` a partir da raiz: verde nos três serviços.

**Contra a stack, depois.** Imagem do `extracao` reconstruída, `docker compose up -d`, e uma
corrida de 20 min do `scripts/trafego.sh` — 202 Vídeos aceitos, 0 recusados, 192 `CONCLUIDO` e
10 `FALHOU`. Sobre as 203 Extrações medidas:

| | concluida | falhou |
|---|---|---|
| observações | 193 | 10 |
| média | 1,34 s | 0,051 s |
| p50 | 0,42 s | ≤ 0,1 s |
| p90 | 5,2 s | ≤ 0,1 s |
| p95 | 7,6 s | ≤ 0,1 s |
| p99 | 9,5 s | ≤ 0,1 s |

As concluídas ocuparam **seis** buckets — 17 até 0,25 s, 114 até 0,5 s, 20 até 1 s, 3 até
2,5 s, 19 até 5 s e 20 até 10 s —, contra o único bucket de antes. É o defeito indo embora:
onde o 092 leu `NaN` em todas as amostras de uma janela de uma hora, lê-se número.

Duas coisas que a corrida disse e que o desenho aceita: as 10 falhas caíram todas no primeiro
bucket (toda falha ali é recusa do ffprobe), e os cinco limites acima de 10 s ficaram vazios —
eles cobrem o regime de teto, que tráfego sintético saudável não produz, e é justamente no dia
em que a Extração encosta no teto que ninguém tem tempo de reconfigurar histograma.

Uma conta não fecha, e fica registrada em vez de arredondada: o censo do `trafego.sh` deu **192
`CONCLUIDO`** e a métrica contou **193 `concluida`** — uma Extração a mais que Vídeo concluído,
com as falhas batendo em 10 dos dois lados. O candidato natural é o comando duplicado que o
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) prevê: duas réplicas extraem o mesmo
Vídeo, as duas concluem, e a transição idempotente do `videos` conta o Vídeo uma vez só. Não foi
reconciliado — a retenção do Prometheus morreu no `down` que veio depois —, e não muda nenhuma
conclusão deste ticket, que é sobre a forma da distribuição e não sobre o denominador.

O `scripts/smoke.sh` completo passou, com o passo 12 cobrando as duas expressões novas do painel
de quantil: 162 amostras não-`NaN` em cada uma, e 14 queries do painel no total.
