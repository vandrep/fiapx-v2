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

## Condenados pelo 025

Nenhuma das candidatas óbvias listadas acima apareceu. O 025 condenou **três defeitos de
correção**, não de vazão — e o ticket segue bloqueado pelo 026, porque só ele pode acrescentar
os de vazão. Cada um com o número que o condena:

1. **Evento terminal descartado quando chega fora de ordem.** `ExtracaoIniciada` e
   `ExtracaoConcluida` vêm em filas independentes e sem ordem entre si; a `Concluida` que chega
   primeiro não casa o predecessor `PROCESSANDO`, altera zero linhas e recebe ack. Vídeo preso
   em `PROCESSANDO` para sempre, **com o `.zip` já no bucket** — os censos somam 46 presos, e
   os 45 que a conferência contra o MinIO cobriu tinham todos o `.zip` lá. Incidência: 11/400
   (2,75%) sob pico com uma réplica reiniciada, **34/39** depois de o
   `videos` cair e voltar. É a perda de requisição que o enunciado proíbe, e nenhuma varredura
   existente a alcança.
2. **A marca do ADR 0003 pode mentir.** `publish-confirms` é `false` por default no conector
   (verificado no `@ConnectorAttribute` do smallrye-reactive-messaging-rabbitmq 4.32.1), então
   o envio completa antes de o broker confirmar. Medido: 3 Vídeos em `RECEBIDO` com
   `comando_publicado_em` preenchido e comando nenhum no broker — e como a varredura filtra por
   marca nula, ela nunca os reconsidera.
3. **A varredura de órfãos no boot do `extracao` apaga o scratch das réplicas vivas.** O volume
   nomeado é compartilhado por todas as réplicas, e o Javadoc de `limparOrfaosNoBoot` assume
   exclusividade. Medido: duas réplicas com `Error submitting a packet to the muxer: No such
   file or directory` no instante do boot de uma terceira, e um h264 válido entregue ao usuário
   como `ARQUIVO_INVALIDO`.

**Condição de aceite do 2, e ela não é opcional.** A varredura do ADR 0003 nunca foi observada
republicando: a rodada `mata-videos` do 025 existia para isso e o que produziu foi este defeito.
Enquanto 1 e 2 existirem, o cenário que a exercitaria está envenenado por eles — a demonstração
só é possível depois da correção. Logo, o 2 só fecha com uma rodada `mata-videos` que mostre a
varredura republicando de fato; sem ela, o `docs/arquitetura.md` continua com a ressalva de
"afirmada e não verificada" na linha da tabela.

O 1 e o 2 são candidatos a **ADR**: mexem em garantia declarada e as alternativas são reais
(reordenar no consumidor, tolerar a chegada fora de ordem no `UPDATE`, ligar `publish-confirms`,
ou estender a varredura para Vídeos parados). O 3 é local e provavelmente não paga ADR.
