# O `Scope` do `Rastro` abre numa thread e fecha noutra

- id: 063
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Levantado numa revisão do [ticket 059](059-tres-sinais-nos-tres-servicos.md) e carregado para o
[ticket 061](061-travamento-raro-com-o-sdk-desligado.md), que pedia investigá-lo "junto". O 061
achou outra causa e fechou; este risco ficou, e **a razão escrita lá para minimizá-lo caiu com
a medição do 062**.

## O risco

`Rastro.naMensagem` e `Rastro.emTorno` chamam `span.makeCurrent()` na thread que subscreve e
`escopo.close()` no callback de término, que pode ser outra thread. O `QuarkusContextStorage`
guarda o contexto no contexto duplicado do Vert.x quando existe um — e aí o par abre/fecha é
por contexto, não por thread, e atravessar thread é justamente o desenho. Mas quando **não** há
contexto duplicado, ele cai no armazenamento por `ThreadLocal`: abrir numa thread e fechar em
outra vaza o contexto na primeira e corrompe a segunda.

O 061 escreveu que isso era teórico no caminho que travava, porque "com o SDK desligado o span
nasce sem gravar e nenhum escopo chega a ser aberto". **Isso é falso** — ver
[062](062-a-chave-que-nao-desliga-o-sdk.md): `quarkus.otel.sdk.disabled` não impede o span de
gravar, o guarda por `isRecording()` nunca dispara, e o escopo é aberto em toda execução, em
todo perfil. O risco, portanto, não tem nenhuma configuração que o desligue.

O que segue verdadeiro é que **nenhum sintoma foi medido**: nas sondas do 061 o caminho do Vídeo
sempre rodou sobre contexto duplicado (`isOnVertxThread=true`, contexto não nulo), que é o caso
seguro.

## O que investigar

- Existe algum caminho, nos três serviços, em que `makeCurrent()` aconteça **fora** de um
  contexto duplicado do Vert.x? Candidatos: o `@Scheduled` da varredura de órfãos, o boot, o
  consumidor da DLQ, e qualquer coisa que o Mutiny desloque para um pool sem propagação.
- Se existir, ele produz sintoma observável — trace partido, contexto vazado, log pendurado no
  span errado — ou só ruído?
- Vale trocar o par `makeCurrent()`/`close()` por uma forma que não dependa de onde a cadeia
  termina (por exemplo, prender o contexto no `Uni` em vez de no armazenamento corrente)?

## O que entregar

Ou a demonstração de que o risco é inalcançável nos três serviços — com o caminho examinado
nomeado, não "não achei" —, ou a correção, medida. Vale o mesmo critério do 061: sem
diagnóstico, nada de patch por palpite.

## Dependências

Nenhuma. O 061 está fechado e o 062 registra a medição que reabriu este risco.

## Resolução

**O risco é alcançável, e foi medido: `extracao.frames` cai fora do contexto duplicado em toda
Extração, e fecha o escopo noutra thread.** Não é teórico, não depende de configuração e não
tem sintoma "só ruído" — ele deixa o span encerrado como contexto corrente da thread que abriu,
e a árvore do trace sai errada por causa disso. Corrigido nos três serviços, com A/B na mesma
sonda.

### Como foi medido

Uma sonda temporária dentro do `Rastro` registrou, nos dez pontos de instrumentação dos três
serviços, a thread e o `Vertx.currentContext()` no `makeCurrent()` e no `close()`, mais o span
corrente antes e depois de cada um. O roteiro é `./mvnw test` a partir da raiz — a suíte
exercita os quatro `naMensagem` e os seis `emTorno` com RabbitMQ, LocalStack e `ffmpeg` de
verdade.

| Ponto | Serviço | Abriu sobre | Fechou |
|---|---|---|---|
| `extracao.extrair-video` | `extracao` | contexto duplicado (4/4) | outra thread em 1 de 4 — **seguro**, o escopo é do contexto |
| `extracao.baixar-video` | `extracao` | contexto duplicado (4/4) | outra thread em 2 de 4 — seguro |
| **`extracao.frames`** | `extracao` | **sem contexto Vert.x (4/4)** | **outra thread nas 4** |
| **`extracao.gravar-pacote`** | `extracao` | **sem contexto Vert.x (3/3)** | mesma thread nas 3, por acaso |
| `extracao.tentativas-esgotadas` (DLQ) | `extracao` | contexto duplicado (1/1) | mesma thread |
| `videos.gravar-video` | `videos` | contexto duplicado (28/28) | mesma thread nas 28 |
| `videos.abrir-pacote` | `videos` | contexto duplicado (5/5) | mesma thread nas 5 |
| `videos.extracao-falhou` | `videos` | contexto duplicado (1/1) | mesma thread |
| `notificacao.video-falhou` | `notificacao` | contexto duplicado (2/2) | mesma thread |
| `notificacao.enviar-email` | `notificacao` | contexto duplicado (2/2) | mesma thread |

Os dois pontos expostos chegam ali pelo mesmo caminho, e ele é o que o javadoc do
`FfmpegExtracaoDeFramesAdapter` já descrevia sem tirar essa conclusão: **a cadeia segue na
thread que completou o download do MinIO**, que é do SDK da AWS e não do Vert.x — em uma das
Extrações, a `InnocuousThread-1`, do pool comum da JVM. O `videos` não chega lá porque o
`ArquivoMinioAdapter` de lá devolve a continuação ao contexto de chamada (`noContextoDeChamada`,
que existe por outro motivo: a sessão do Panache).

O `@Scheduled` e o boot, candidatos citados no ticket, estão **fora**: nem o
`ReconciliacaoScheduler` do `videos` nem o `EspacoDeTrabalhoAdapter` do `extracao` tocam o
`Rastro`. O consumidor da DLQ está dentro, e é seguro.

### O sintoma, e por que ele não é ruído

Sem contexto duplicado o armazenamento é o `MDCEnabledContextStorage`, sobre uma `ThreadLocal`.
O `ThreadLocalContextStorage` só restaura quando o contexto corrente é o mesmo que ele anexou,
então o `close` vindo de outra thread é **ignorado em silêncio** (log `FINE`) — e a thread que
abriu fica com o span, já encerrado, como contexto corrente. O MDC não tem essa guarda: ele é
reescrito na thread errada.

Medido, antes da correção: `extracao.frames` e `extracao.gravar-pacote` nasciam **filhos de
`extracao.baixar-video`**, um span que já havia encerrado e que só seguia corrente porque
ninguém o soltou. Depois: os dois nascem filhos de `extracao.extrair-video`, e nenhuma thread
termina o ciclo carregando span encerrado.

### A correção

Uma regra, aplicada nas três cópias do `Rastro`: **escopo só atravessa fronteira assíncrona
quando está preso ao contexto duplicado do Vert.x; fora disso, abre e fecha na mesma thread.**

- `emTorno` não precisa do escopo aberto durante a espera — quem lê o contexto corrente é a
  instrumentação que monta a requisição, e ela roda no disparo. O escopo virou
  try-with-resources em volta do disparo; o span continua vivo até a conclusão.
- `naMensagem` precisa: é o escopo aberto que faz a publicação seguinte ser filha do consumo.
  Ele continua atravessando thread, e agora **só** quando há contexto duplicado. Sem ele, não
  abre escopo nem MDC e registra um `WARN` — pela medição, isso não acontece em consumo de
  mensagem em nenhum dos três serviços, então a linha é sinal de mudança, não ruído de rotina.

`EscopoNaoAtravessaThreadTest` (no `extracao`) trava as duas: ele abre numa thread, completa
noutra, sem contexto Vert.x nenhum, e reprova nas duas formas antes da correção.

### A alternativa que o ticket nomeou, e por que ela não é a saída

O ticket pergunta se vale "prender o contexto no `Uni` em vez de no armazenamento corrente". Não
vale, e o motivo é estrutural: a cadeia de uma Extração **sai do `Uni`**. Ela atravessa o `core`
por gateways que devolvem `CompletableFuture`, e o `core` não pode carregar tipo do OpenTelemetry
— é a regra que o `ArchitectureConstraintsTest` cobra e o [ADR 0004](../../adr/0004-camada-de-observabilidade.md)
justifica. Não há, portanto, onde pendurar um contexto por cadeia que sobreviva do consumidor até
o adapter: o contexto duplicado do Vert.x **é** esse carregador, e é o único que as três camadas
enxergam sem se conhecerem.

O que a correção faz é outra coisa, e é a que restava: onde o carregador existe, usá-lo
explicitamente e não por acidente (`naMensagem`); onde ele não existe, **remover a necessidade
dele** — o `emTorno` não precisa do escopo aberto durante a espera, só no disparo.

Fica um preço, e ele está escolhido de olhos abertos: no ramo sem contexto duplicado, o
`naMensagem` perde o encadeamento do trace daquele consumo em vez de corromper a thread. É o
menor dos dois males — um trace partido some numa busca, um contexto pendurado numa thread do
pool comum da JVM contamina o trabalho de quem vier depois — e, pela medição, o ramo não é
alcançado em serviço nenhum.

### Uma consequência de código que a medição não pega

O `emTorno` antigo chamava `MDC.remove(ID_VIDEO)` ao encerrar, e essa chave não é dele: quem a
põe é o `naMensagem` que o envolve. Ou seja, a primeira ida ao MinIO de uma Extração apagava o
`idVideo` do MDC do consumo, e o que fosse logado depois dela sairia sem o campo. Com o
encerramento separado por método — `emTorno` mexe só no span, o consumo solta o que prendeu —
isso deixa de acontecer. Não está medido: é leitura de código, e está escrito aqui como leitura.

### De quebra: um desconhecido do 059 virou fato medido

O [059](059-tres-sinais-nos-tres-servicos.md) registrou como *não verificado* que o log emitido
de dentro do pipeline de `ffmpeg` sai sem `idVideo` e sem `trace_id`, por rodar em
`Infrastructure.getDefaultWorkerPool()`, fora do contexto duplicado. A sonda deste ticket mede
exatamente essa perna: `extracao.frames` roda com `Vertx.currentContext()` **nulo** nas 4 de 4
Extrações. O desconhecido continua valendo como estava escrito — e agora está medido, não
suposto. Este ticket não muda esse comportamento: o `emTorno` nunca teve `idVideo` para pôr no
MDC, e pôr o span corrente numa thread que ninguém limpa é o defeito que ele acabou de remover.

### Um limite da medição, e um achado que não é deste ticket

A suíte roda com `quarkus.otel.sdk.disabled=true`, e sob essa chave **não há span de servidor
HTTP** — o `marcar` do `videos` vê contexto vazio dentro do `Resource` (medido). Por isso os
spans do MinIO do `videos` aparecem como raízes na sonda: é artefato do perfil de teste, não
afirmação sobre produção, onde o ticket 059 verificou o encadeamento pelo `smoke.sh`. O que a
suíte mede sem ressalva é thread e contexto, que é o objeto deste ticket; a instrumentação de
mensageria, essa, roda em teste e o pai da mensagem aparece nas sondas.
