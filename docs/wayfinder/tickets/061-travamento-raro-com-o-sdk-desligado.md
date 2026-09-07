# Extração trava, raramente, com o SDK de observabilidade desligado

- id: 061
- label: ready-for-agent
- status: fechado
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

## Resolução

A causa raiz **não é o SDK de observabilidade**, e o título deste ticket é o nome de uma
correlação que a medição desfez. É a tolerância a falhas por interceptor nos adapters de I/O.

### Como foi encontrada

1. **Reproduzido.** `scripts/carga/travamento.sh` (novo neste ticket) repete ciclos
   `POST /videos` → `CONCLUIDO` com teto por ciclo e, no primeiro que estoura, coleta filas,
   consumidores, o *scratch* de cada réplica, um thread dump por `SIGQUIT` e o estado do Vídeo
   — tudo enquanto a réplica ainda está presa, que é o único momento em que a evidência existe.
   O travamento apareceu no ciclo 9 da primeira rodada, com o sintoma exatamente como descrito
   acima: `messages_unacknowledged=1`, thread dump só com event loops ociosas, zero linhas de
   log.

2. **Localizado pelo disco, antes de tocar em código.** O diretório da tentativa existia e
   estava **vazio**. Isso já elimina metade do pipeline: `ExtracaoIniciada` foi publicado *e
   confirmado*, `prepararNovo` completou, e o download do MinIO nunca produziu arquivo. O
   `/proc/net/tcp6` das duas réplicas confirmou: nenhuma conexão com o MinIO aberta — a
   requisição não estava em voo, estava *ausente*.

3. **Localizado ao nível da linha, com sondas.** Uma imagem com log em cada fronteira
   assíncrona travou de novo e disse onde: a última linha é `baixarVideo entrada`, no
   `ArquivoMinioAdapter`. A **primeira linha do corpo** de `ArquivoMinioClient.baixar` nunca
   sai. Entre as duas só existem o `Rastro.emTorno` e o interceptor do fault tolerance — e nos
   ciclos que passam essa mesma travessia **troca de thread** (`executor-thread-1` →
   `executor-thread-2`), o que só o interceptor faz.

4. **Mecanismo, lido no fonte do SmallRye 6.10.0.** Numa operação verdadeiramente assíncrona —
   e todo método que devolve `CompletionStage` aqui é uma —, o interceptor monta
   `RememberEventLoop -> ThreadOffload`. O `RememberEventLoop` lê o contexto Vert.x corrente
   (a sonda confirmou: `isOnVertxThread=true`, contexto não nulo) e o guarda; o `ThreadOffload`,
   vendo esse `Executor`, deixa de invocar o método na thread do chamador e o **reagenda no
   mesmo contexto Vert.x** em que a cadeia do consumidor já está rodando — o `VertxExecutor`
   despacha por `runOnContext`/`executeBlocking` daquele contexto. É esse reagendamento que
   nunca acontece.

   **O que está medido e o que é leitura.** Medido: o reagendamento existe (a troca de thread),
   o alvo é o contexto do chamador (a sonda), e a tarefa reagendada nunca roda (nenhuma linha,
   nenhum socket, nenhuma retentativa, threads do pool ociosas no dump). Leitura, e a única que
   encaixa nos três: a tarefa fica atrás da própria cadeia que espera por ela na ordenação
   daquele contexto — a cadeia do consumidor `@Blocking` só termina quando este download
   terminar. O passo final não foi observado diretamente porque **não pode ser**: qualquer log
   dentro da janela faz o defeito sumir (ver o efeito de observador em 5). A confirmação veio
   de A/B causal, que é o instrumento certo quando a sonda destrói o fenômeno.

5. **Confirmado causalmente, não por dedução.** A/B no mesmo host, mesmo roteiro:

   | Variante | Ciclos | Travamentos |
   |---|---|---|
   | Imagens publicadas, com `@Retry` + `@AsynchronousNonBlocking` | ~60 | **4** |
   | Mesmo código sem as duas anotações | 90 | 0 |
   | Correção entregue (retentativa do Mutiny) | 90 | 0 |

   Vale registrar um efeito de observador que quase custou o diagnóstico: **acrescentar uma
   linha de log dentro da janela suspeita faz o defeito sumir** (90, 90 e 90 ciclos limpos em
   três variantes instrumentadas). Foi por isso que a confirmação veio de A/B causal, e não de
   um log que provasse o passo final.

### A correção

A retentativa do [ADR 0001](../../adr/0001-politica-de-falhas.md) deixou de vir do interceptor
e passou a ser `onFailure().retry()` do Mutiny, dentro da própria cadeia — mesma contagem (3),
mesma espera fixa (2 s), mesmo recorte (`Exception`, não `Error`). Nos três serviços, porque a
construção era a mesma nos três: `ArquivoMinioClient` do `extracao` e do `videos`, e
`MailerEmailClient` do `notificacao`. O `videos` chama da borda HTTP, que roda sobre o mesmo
tipo de contexto; o `notificacao`, de um consumidor `@Blocking`, que é exatamente a forma
reproduzida aqui.

`quarkus-smallrye-fault-tolerance` saiu dos três `pom.xml`, e uma regra nova do
`ArchitectureConstraintsTest` — nas três cópias — barra qualquer import de
`org.eclipse.microprofile.faulttolerance` ou `io.smallrye.faulttolerance` em código de
produção, com o mecanismo na mensagem. O `RetryComCompletionStageTest`, que travava o
comportamento do interceptor, deu lugar ao `RetentativaDoMinioTest`, que trava a **política**:
blip absorvido, armazenamento persistentemente fora falhando na quarta tentativa em vez de
insistir para sempre.

### O achado colateral, e é o mais caro

**`QUARKUS_OTEL_SDK_DISABLED=true` não desliga a instrumentação — desliga a exportação.** Com a
chave ligada (por variável de ambiente *e* por propriedade de sistema, Quarkus 3.31.3) o span
continua sendo um `SdkSpan` que grava, e o `Rastro` continua abrindo escopo e escrevendo o
`idVideo` no MDC. Ou seja: o guarda por `isRecording()` **nunca dispara**, e a frase deste
ticket — "o único trecho de código que se comporta diferente com o SDK desligado é o guarda por
`Span#isRecording()`" — descrevia uma diferença que não existe. As duas pernas do A/B do 059
eram, no código, a mesma perna; os 3 travamentos "com o SDK desligado" contra 0 "com o SDK
ligado" mediram ruído.

Isso também derruba a razão escrita aqui para excluir a pista do `Scope` aberto numa thread e
fechado noutra. A conclusão continua valendo — não era ela —, mas o argumento (“com o SDK
desligado o span nasce sem gravar, então nenhum escopo chega a ser aberto”) era falso: o escopo
**é** aberto, inclusive no caminho que travava. O risco separado que a seção descreve, o de
`makeCurrent()` fora de contexto duplicado, continua aberto e continua sem sintoma medido.

O achado também virou teste: `SdkDesligadoAindaGravaTest` mede, na própria suíte — que roda
com `%test.quarkus.otel.sdk.disabled=true` —, que o span continua gravando. Ele não afirma que
isso é desejável; afirma que é o comportamento atual e que ele não pode mudar em silêncio. Se um
upgrade do Quarkus fizer a chave desligar de verdade, o teste reprova e manda reabrir o 062 em
vez de deixar a próxima investigação repetir este beco sem saída.

Os comentários que afirmavam o contrário foram corrigidos onde estavam: os três `Rastro.java`,
os três `application.properties`, o `docker-compose.carga.yml`, o
[ADR 0004](../../adr/0004-camada-de-observabilidade.md) e o `docs/arquitetura.md`. O que **não**
foi decidido aqui — o que o overlay de carga deve fazer agora, e se a comparação com os
tickets 025–028 se sustenta — virou o
[ticket 062](062-a-chave-que-nao-desliga-o-sdk.md).

### Validação

- `./mvnw test` da raiz: **437 testes**, 0 falhas, 0 erros (138 em `videos`, 274 em `extracao`,
  25 em `notificacao`).
- `scripts/carga/travamento.sh`, 6 rodadas de 15 ciclos com as imagens corrigidas: **90 ciclos,
  0 travamentos**.
- `scripts/smoke.sh` completo contra o Compose, com as três imagens construídas desta correção:
  passou nos 11 passos, incluindo o rastro correlacionado dos três serviços e o ciclo do Vídeo
  com a observabilidade derrubada.
