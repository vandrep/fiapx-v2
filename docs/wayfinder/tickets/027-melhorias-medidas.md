# Melhorias justificadas pela medição

- id: 027
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por:

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


## Condenados pelo 026

O 026 **confirmou** a linearidade (eficiência 0,88 em 6 réplicas contra critério de 0,80), então
não condenou o desenho. Condenou **um** ponto, e é o primeiro candidato de vazão deste ticket —
todos os do 025 são de correção.

4. **O `ffmpeg` abre 20 threads contra uma cota de 2 CPUs.** `nproc` dentro do container devolve
   os 20 núcleos do host, não a cota do cgroup, então o `ffmpeg` default dimensiona o pool pelo
   número errado e as 20 threads são estranguladas em bloco pelo CFS. Medido no mesmo fixture de
   2 min: **20,84 s com o default, 14,14 s com `-threads 2` — 32%**, e o valor com `-threads 2`
   bate quase exato com 2 núcleos dedicados via `taskset` (14,02 s), o que fecha o diagnóstico: a
   perda inteira era sobre-assinatura contra a cota.

   É também a hipótese mais provável para os 12% de perda de eficiência em `N=6`, onde há ~120
   threads de `ffmpeg` disputando 20 núcleos sob seis cotas independentes — a CPU por réplica cai
   de ~196% para ~170% conforme `N` cresce, isto é, cada réplica deixa de conseguir gastar a
   própria cota. Essa parte **não foi testada na varredura**, só isoladamente.

   O que torna isto não-trivial e provavelmente merecedor de discussão: o número de threads certo
   **depende da cota**, que é config de implantação, não de código. Fixar `-threads 2` no adapter
   acopla o serviço a um `cpus=2` que só existe no overlay de carga; o `docker-compose.yml` da
   demo não põe teto nenhum, e ali o default de 20 threads é o **certo** (3,04 s medidos sem
   teto). As alternativas reais são ler a cota do cgroup em runtime, expor um
   `fiapx.extracao.threads` configurável, ou não mexer e documentar. Provavelmente **não** é ADR
   — é reversível —, mas é decisão, não digitação.

**Nota de método herdada do 026, que vale para as medições de "depois" deste ticket.** O harness
ganhou um sexto portão de validade — continuidade da série de telemetria — porque duas das doze
corridas do 026 foram corrompidas pelo *host suspendendo* no meio, o que estica o denominador sem
falhar nenhum dos outros cinco critérios. Toda medição de antes/depois aqui roda sob
`systemd-inhibit --what=sleep:idle`, ou repete o mesmo erro.

O 3 ganha contexto novo: o 026 o **contornou por protocolo** (nada boota com trabalho em voo) e
percorreu 12 corridas com até 6 réplicas sem incidência — o que confirma que é defeito de boot, e
não de regime, mas não o corrige.
