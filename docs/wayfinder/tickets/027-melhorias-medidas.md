# Melhorias justificadas pela medição

- id: 027
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 025, 026

## Question

**O que a medição condenar, e nada além.**

Este ticket nasce bloqueado e com o corpo vazio de propósito. É a defesa contra o vício
clássico de teste de performance: decidir a melhoria antes de o número chegar, e depois
procurar o número que a justifique. As candidatas óbvias — segundo consumidor no `extracao`,
`prefetch` maior, pool do Postgres, intervalo da varredura de reconciliação, `max-body-size`,
paralelismo do `ffmpeg` — **não entram aqui até que [025](025-carga-conservacao.md) ou
[026](026-linearidade-horizontal.md) apontem uma delas com um número**.

O que preenche este ticket, quando os dois fecharem:

- as melhorias **aceitas**, cada uma com a medição que a justificou e a medição de depois;
- as melhorias **recusadas**, cada uma com o número ao lado — mesmo padrão da tabela *"O que
  foi recusado, e por quê"* de `docs/arquitetura.md`, que é o que impede a recusa de virar
  esquecimento.

Se alguma escolha aqui for irreversível e envolver alternativas reais, ela ganha **ADR**. As
decisões de método de medição dos dois tickets anteriores não ganharam, e por isso: são
reversíveis e baratas.

Se a medição não condenar nada, este ticket fecha **sem mudança de código** e com essa frase na
resolução. Isso é um resultado, não um fracasso.
