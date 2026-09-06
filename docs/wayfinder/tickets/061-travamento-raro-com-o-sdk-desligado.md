# Extração trava, raramente, com o SDK de observabilidade desligado

- id: 061
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Medido durante o ticket 059, ao comparar o custo da instrumentação com e sem ela. Com
`QUARKUS_OTEL_SDK_DISABLED=true` — a configuração que o `docker-compose.carga.yml` passou a
usar no 059 — uma Extração ocasionalmente **para no meio e não volta**.

O sintoma é preciso e sempre o mesmo:

- o Vídeo chega a `PROCESSANDO` (logo, `ExtracaoIniciada` foi publicado e o pipeline começou);
- a mensagem fica **unacked** em `extracao.extrair` (`messages_unacknowledged=1`);
- **nenhuma thread** está trabalhando — o thread dump da réplica só mostra event loops ociosas,
  as conexões AMQP e o scheduler. Não é um `ffmpeg` pendurado nem I/O bloqueado;
- **nenhuma linha de log**: nem erro, nem o aviso `desligamento em curso` do `DrenoDaExtracao`;
- como `max-outstanding-messages=1`, aquela réplica fica **presa para sempre**. Com as duas
  réplicas presas, a demo inteira para de processar;
- um `docker compose restart extracao` reenfileira a mensagem e ela é processada normalmente —
  o comando e o Vídeo estão íntegros.

## A medição

Cenário: logo após `docker compose up -d --force-recreate` dos três serviços, dez ciclos
`POST /videos` → `CONCLUIDO`, com teto de 45 s por ciclo. Três rodadas de cada variante,
alternadas, no mesmo host e com a mesma stack de apoio.

| Variante | Ciclos | Travamentos |
|---|---|---|
| Imagens do 059, `QUARKUS_OTEL_SDK_DISABLED=true` | 30 | **1** |
| Imagens pré-059 (sem instrumentação nenhuma) | 30 | 0 |

Fora do A/B, em execuções exploratórias no mesmo dia: mais **três** travamentos em ~40 ciclos
com o SDK desligado, e **nenhum** em ~20 ciclos com o SDK **ligado** — que é a configuração da
demo e a que o `smoke.sh` exercita.

## O que já foi descartado

- **Não é o container substituído**: os dois consumidores da fila estavam vivos no momento do
  travamento (`rabbitmqctl list_consumers`).
- **Não é o `ffmpeg` nem o MinIO**: nenhuma thread de trabalho existe no dump.
- **Não é o dreno**: o `DrenoDaExtracao` loga quando recusa entrada, e não logou.
- **Não é a variável não pegar**: com ela ligada, zero traces chegam ao Tempo — o SDK está mesmo
  desligado.

## Hipótese aberta

O único trecho de código que se comporta diferente com o SDK desligado é o guarda por
`Span#isRecording()` do `Rastro` (`framework/observabilidade/Rastro.java`), que devolve a cadeia
crua em vez de abrir escopo. O caminho "cru" é, em tese, idêntico ao de antes do 059 — o que
torna a hipótese insatisfatória e é exatamente por isso que este ticket existe em vez de um
patch às cegas. A alternativa é que o 059 apenas mudou o *tempo* do pipeline o bastante para
expor uma corrida que já existia no conector.

## Uma pista examinada e descartada, e um risco separado

Numa revisão do 059 levantou-se o `Scope` do `Rastro` como candidato: `span.makeCurrent()` roda
na thread que subscreve e `escopo.close()` no callback de término, que pode ser outra. **Não
explica este defeito**: com o SDK desligado o span nasce sem gravar e o `Rastro` devolve a cadeia
crua — nenhum escopo chega a ser aberto no caminho que trava.

O risco, porém, é real no caminho **com** o SDK ligado, e vale investigar junto: quando
`makeCurrent()` acontece fora de um contexto duplicado do Vert.x, o `QuarkusContextStorage` cai
no armazenamento por `ThreadLocal`, e aí abrir numa thread e fechar em outra vaza o contexto na
primeira e corrompe a segunda. Nas execuções do 059 isso não produziu sintoma — o caminho de
Vídeo roda sobre contexto duplicado —, mas é a mesma família de problema e o mesmo código.

## O que entregar

Causa raiz identificada e coberta por teste, ou — se a causa for anterior ao 059 — o registro
disso com a mesma clareza. Enquanto não houver diagnóstico, **não aplicar correção por palpite**:
o sintoma é raro o suficiente para um patch errado parecer que funcionou.

## Dependências

Nenhuma. O 059 está fechado; este ticket carrega o defeito que a medição dele encontrou, no
mesmo espírito do ticket 027.
