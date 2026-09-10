# Os limites de bucket de `fiapx.extracao.duracao` são de milissegundos, e o quantil é `NaN`

- id: 094
- label: ready-for-agent
- status: aberto
- assignee:
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
