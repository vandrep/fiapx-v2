# O Vídeo é aceito no commit da linha, não no publish

- id: 104
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-13, SHA inicial b5ec076)
- bloqueado-por:
- prioridade: P1

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)).

## O problema

`EnviarVideoUseCase` encadeia objeto no MinIO → `INSERT` com marca nula → publish de
`ExtrairVideo` → marca. Se o publish falhar, por exemplo com o broker bloqueado por alarme de
disco ou memória, a exceção sobe e o `POST /videos` responde `500`. Nesse ponto o objeto e a
linha `RECEBIDO` já existem, e a reconciliação do
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) republica o comando: o Vídeo é
processado e aparece na listagem, enquanto o cliente acredita que falhou e pode reenviar. Isso
é leitura do código, não medição.

## Decisão

**O aceite é o commit da linha.** Depois do `INSERT`, a garantia do ADR 0003 já cobre o
comando, então a falha do publish não falha a requisição: o `POST` responde `202`, com a marca
nula, e a varredura publica depois. O publish na borda ganha um **teto de tempo**, para que um
broker bloqueado não segure a requisição; valor a decidir por medição, coerente com as esperas
do ADR 0001.

Alternativas recusadas: desfazer linha e objeto e responder `503` (compensação que também pode
falhar) e manter o `500` ambíguo.

## Critérios de aceite

- [x] Teste do use case: publish que falha depois do `INSERT` resulta em `202` com Vídeo
      `RECEBIDO` e marca nula; a reconciliação o publica depois.
- [x] Teste com publish que não responde: a requisição termina dentro do teto e responde `202`.
- [x] Falha **antes** do commit (MinIO ou `INSERT`) continua sem `202`.
- [x] Contrato em `docs/contratos/http-videos.md` diz o que o `202` promete.
- [x] Emenda no ADR 0003 e linha em "Decisões até aqui" no mapa.
- [x] `./mvnw test` verde a partir da raiz.

## Resolução

Implementado em 2026-09-13 sobre `develop @ b5ec076`.

**Core.** `EnviarVideoUseCase` apresenta o Vídeo logo depois do `INSERT`, que é o aceite, e só
então publica. O publish ganha `copy().orTimeout(teto).exceptionally(null)`. O `copy()` é o que
faz o teto limitar a **espera da requisição**, e não o publish: o `thenCompose` da marca dentro de
`PublicarExtrairVideo` continua pendurado no envio original, e um broker que confirma depois do
teto ainda grava a marca. Tirar o `copy()` reprova `publishConfirmadoDepoisDoTetoAindaGravaAMarca`,
e isso foi conferido. A varredura não ganhou teto, porque o ticket pede teto só na borda.

**Teto: 2 s**, em `fiapx.mensageria.teto-do-publish-no-envio`, com o default no código
(`VideosConfiguration`).

**Framework.** O teto trouxe um defeito que o teste de use case não alcança. Vencido o prazo, o
envio completa na thread `ForkJoinPool.commonPool-delayScheduler`. O `.invoke(rastro::marcar)` do
`VideosResource` seguia ali e deixava o `idVideo` no MDC daquela thread, que é compartilhada pelo
JVM inteiro. É o vazamento do ticket 063. O teste
`publishQueNaoRespondeNaoDeixaOIdVideoNoMdcDaThreadDoTimer` reprovou com o `idVideo` lido de
dentro da thread, e só então veio a correção. A ponte `doController` devolve a continuação ao
contexto Vert.x por `framework/vertx/ContextoDeChamada`, que o `ArquivoMinioAdapter` já fazia em
cópia própria; a revisão apontou a duplicação e as duas passaram a usar o helper.
`RabbitExtracaoSender` registra a recusa do envio, porque ela não vira mais `500`.

### Medição

Contra o Compose, com a imagem do `videos` construída localmente e o fixture `video-valido.mp4`:

- **Broker sadio, 20 envios:** `202` em 61–256 ms, com o POST inteiro (MinIO, `INSERT` e publish).
- **Broker em alarme de disco** (`rabbitmqctl set_disk_free_limit 100000GB`), **3 envios:** `202`
  em 2,028 / 2,028 / 2,031 s, em `RECEBIDO` e sem marca. Depois de ~37 s ainda sem marca, o limite
  voltou a 50MB. Em até 10 s os três tinham a marca e estavam em `CONCLUIDO`, sem linha de
  reconciliação no log: a confirmação chegou antes da folga de 1 min.
- **Imagem anterior** (`ghcr.io/vandrep/fiapx-videos:latest`), **mesmo alarme:** o `POST`
  **pendurou** 60 s sem nenhum byte até o `curl --max-time 60` desistir.

A medição corrige a leitura em "O problema": com o broker bloqueado por alarme não havia `500`,
havia requisição presa. Com confirms ligados, o publish nem falha nem confirma. O `500` só seria a
resposta para recusa explícita, como canal fechado ou `nack`, e essa não foi medida.

O valor de 2 s cabe numa das duas esperas do ADR 0001, que já aceita segurar a borda por 4 s num
blip do MinIO, e dá cerca de 8× o POST sadio mais lento. Errar para baixo é barato: só aumenta o
número de `202` que saem antes da confirmação, sem duplicar a Extração.

### Validação

- `EnviarVideoUseCaseTest`: 10 testes, 5 novos (falha do publish com reconciliação depois, publish
  travado, confirmação tardia com marca, falha do MinIO, falha do `INSERT`).
- `AceiteNoCommitDaLinhaTest` (novo, `@QuarkusTest` pela borda HTTP): recusa do broker dá `202` sem
  marca; publish travado dá `202` dentro do teto; nada fica no MDC da thread do timer.
- `ReconciliacaoAposPublicacaoInterrompidaTest` foi **invertido**: afirmava que o envio propagava
  a falha. Agora afirma o aceite, e a republicação pela varredura segue com Postgres e RabbitMQ de
  verdade.
- `./mvnw test` a partir da raiz, depois das correções da revisão: **470 testes verdes** (151 `videos`, 290 `extracao`, 29 `notificacao`), `BUILD SUCCESS`.

### Limites que ficam

- O envio que nem falha nem confirma fica **mudo** depois do teto: não há linha de log até a
  varredura republicá-lo.
- O diagrama de sequência do `docs/arquitetura.md` desenha o `202` antes do `ExtrairVideo`. Isso já
  estava impreciso antes do 104, e continua: o `202` sai depois do publish ou do teto. A narração do
  `docs/roteiro-video.md` cita o "passo quatro" daquele diagrama e ainda diz que o comando "já está
  na fila". Não mexi em nenhum dos dois, porque renumerar o diagrama muda a narração cronometrada.
  Fica para o 109 ou para quem revisar o roteiro.
- A falha do `INSERT` sem `202` tem teste só no use case. Pela borda HTTP, a falha coberta é a do
  MinIO (`EnvioResisteABlipDoArmazenamentoTest`).
