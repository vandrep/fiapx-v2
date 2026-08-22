# Implementação do serviço extracao

- id: 015
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por: 007, 011

## Question

Worker sem estado. O ffmpeg já está decidido (ticket 006: processo externo, `-xerror`, ZIP
`STORED`, classificação por exit code) e o contrato de mensagens fechou (ticket 007). Falta
o teto de duração, que o ticket 011 já decidiu — é este serviço que tem de impô-lo.

Implementar, test-first, conforme
[`docs/contratos/mensagens.md`](../../contratos/mensagens.md) e o `AGENTS.md` do template:

- Consumidor de `extracao.extrair` e o pipeline: baixar o Vídeo do MinIO pela `chaveVideo`,
  rodar ffmpeg, empacotar em ZIP `STORED`, subir na `chaveDestinoPacote`. Streaming ponta a
  ponta (ticket 005) — nada de `toBytes`.
- Publicação de `ExtracaoIniciada`, `ExtracaoConcluida` e `ExtracaoFalhou`.
- Classificação de falha a partir do exit code **e** do stderr (o exit 8 colide), mapeada
  para `codigoMotivo`. Conferência da contagem de frames contra a duração do `ffprobe`.
- Os dois caminhos de falha: permanente publica direto e dá ack; transitória dá `nack` e
  esgota o `x-delivery-limit`.
- **Consumidor da própria DLQ** `extracao.extrair.dlq`, publicando `TENTATIVAS_ESGOTADAS` —
  sem ele o Vídeo trava em `PROCESSANDO`.
- `@Retry` com backoff de segundos nos adapters de I/O (MinIO), conforme ADR 0001.
- `max-outstanding-messages=1`.
- **Teto de duração de 20 minutos** (ticket 011), lido do `ffprobe` que já roda para
  conferir a contagem de frames. Acima dele, falha **permanente**: publica
  `DURACAO_EXCEDIDA` e dá ack, sem gastar as três entregas.
- Imagem `eclipse-temurin:21-jre-alpine` + `apk add --no-cache ffmpeg`. Scratch em
  `/var/fiapx/extracao/{idVideo}` sobre o volume `fiapx-extracao-scratch`, orçado em 4 GB
  (ticket 011): diretório **apagado-e-recriado** no início de cada tentativa, `finally` por
  mensagem e **varredura no boot** — o worker morre no meio por desenho, então limpeza no
  fim do processo não basta. Frames em disco é o caminho orçado; `image2pipe` alimentando o
  `ZipOutputStream` corta o pico pela metade e fica como otimização **opcional** desta
  implementação, com a ressalva de que fatiar PNGs concatenados briga com a classificação
  por exit code e com a contagem de frames.
- Chaves de objeto chegam prontas nas mensagens — este serviço **não** conhece a convenção
  do ticket 011 nem os nomes dos buckets.
- Health check e a regra nova no `ArchitectureConstraintsTest`.

## Resolução

**25 testes verdes, 17 sem Docker**: as duas exceções de falha, o pipeline do `core` com
dublês de MinIO/ffmpeg/scratch, e o `ArchitectureConstraintsTest` rodam sem container. Só o
`CucumberTest` (2 cenários) e um teste de regressão do `@Retry` sobem RabbitMQ/MinIO reais —
e o `CucumberTest`, por sua vez, roda **ffmpeg de verdade** contra um vídeo real de 3s
gerado por `ffmpeg -f lavfi -i testsrc`, checado no repo em
`extracao/src/test/resources/fixtures/`. Nenhum dublê no pipeline de extração em si: se o
comando ffmpeg estivesse errado, o teste teria pegado.

Camadas: `ArquivoGateway` (baixa/grava no MinIO, sem conhecer convenção de chave — as chaves
chegam prontas na mensagem), `ExtracaoDeFramesGateway` (ffprobe + ffmpeg + ZIP `STORED`, uma
implementação, `FfmpegExtracaoDeFramesAdapter`) e `EspacoDeTrabalhoGateway` (scratch por
Vídeo). `ProcessarExtracaoUseCase` orquestra os três e o `ExtracaoEventosSender`; o
diretório de trabalho é sempre limpo (sucesso ou falha) porque a limpeza mora no próprio use
case, não num `finally` de quem chama — o worker morre no meio por desenho, e um `finally`
do lado de fora simplesmente não executaria.

Quatro achados reais que a especificação não tinha como prever:

- **A sintaxe de `-loglevel` do ticket 006 não compila no ffmpeg 7.1.5.**
  `-loglevel +level+repeat:error` (a recomendação literal da pesquisa) falha com "Invalid
  loglevel" — o manual pede flags separadas por `+` e coladas ao nível, sem `+` inicial nem
  `:`: a forma correta é `-loglevel level+repeat+error`. Descoberto rodando o comando de
  verdade contra o fixture, não por inspeção do manual.
- **`@Retry` do SmallRye Fault Tolerance não intercepta `CompletableFuture`, só
  `CompletionStage` exato.** `CompletionStageSupport.applies` compara
  `CompletionStage.class.equals(returnType)` — um subtipo não conta, mesmo sendo
  `CompletableFuture implements CompletionStage`. E o método anotado não pode ser chamado de
  dentro do próprio bean (self-invocation ignora o proxy do CDI, o interceptor nunca
  dispara). Por isso o retry mora num bean à parte, `ArquivoMinioClient`
  (`CompletionStage`, chamado de fora por `ArquivoMinioAdapter`, que devolve
  `CompletableFuture` para o `core` como o resto do projeto exige). Os dois comportamentos
  ficaram travados em `RetryComCompletionStageTest`, para um upgrade futuro do SmallRye não
  quebrar o retry em silêncio.
- **`@Blocking("nome-do-pool")` não existe nesta versão.** A pesquisa 006 recomendou um pool
  nomeado para isolar o ffmpeg; a única `@Blocking` no classpath desta versão do SmallRye
  Reactive Messaging (`io.smallrye.common.annotation.Blocking`) é um marcador sem parâmetro
  nenhum. Sem corretude em jogo — `max-outstanding-messages=1` já limita o canal a uma
  mensagem em voo — o adapter de ffmpeg ainda assim despacha explicitamente para
  `Infrastructure.getDefaultWorkerPool()`, porque o thread de quem chama
  `ExtracaoDeFramesGateway.processar` pode ser o thread do SDK da AWS que completou o
  download do MinIO, não o worker pool do `@Blocking`.
- **`ubuntu-latest` do GitHub Actions não traz ffmpeg** (conferido no manifesto oficial do
  `runner-images`). Sem isso `./mvnw verify` do ticket 013 quebraria no CI por binário
  ausente, não por código — `.github/workflows/ci.yml` ganhou um passo de
  `apt-get install ffmpeg` antes do `verify`, e o `AGENTS.md` raiz passou a documentar que
  rodar `./mvnw test` também exige ffmpeg/ffprobe no `PATH` do host, não só Docker.

Topologia verificada de verdade, não assumida: `quarkus dev` com Dev Services reais, dump da
API de management do RabbitMQ confirmou `extracao.extrair` como fila quorum com
`x-delivery-limit=3` e `x-dead-letter-exchange=extracao.dlx`, o exchange `extracao.dlx` (tipo
`direct`, criado pelo `auto-bind-dlq`), e `extracao.extrair.dlq` (classic, sem
`x-queue-type` — o `auto-bind-dlq` não fixa tipo) com **uma única** binding vinda do DLX,
apesar de dois canais (`extrair-video` e `extrair-video-dlq`) declararem a mesma fila — a
redeclaração com `queue.declare` default e argumentos idênticos é idempotente, e o boot não
depende de qual dos dois canais sobe primeiro. `exchange.name=""` no canal `extrair-video-dlq`
evita que ele declare ou faça bind a qualquer exchange próprio: ele só consome a fila que o
outro canal já declara. `/q/health` respondeu `UP` para os cinco canais (liveness, readiness
e startup).

**Regra nova no `ArchitectureConstraintsTest`** (propagada idêntica às três cópias):
`ProcessBuilder` só pode aparecer em `framework`, no mesmo espírito da regra de mensageria do
ticket 017 — execução de processo externo é infraestrutura, o `core` só conhece
`ExtracaoDeFramesGateway`.

**Fora do automatizado**: o caminho `TENTATIVAS_ESGOTADAS` (três entregas reais esgotando o
`x-delivery-limit` e caindo na DLQ) não foi exercitado ponta a ponta contra um broker de
verdade — provocar isso exigiria derrubar o consumidor no meio de três tentativas, o que
pareceu desproporcional para esta sessão. `ProcessarTentativasEsgotadasUseCaseTest` cobre o
use case isolado, e a topologia que o sustenta (fila, DLX, DLQ, binding) foi verificada acima.
`image2pipe` como otimização de disco (mencionado na pergunta como opcional) não foi
implementado — frames em disco é o caminho orçado pelo ticket 011, e não há sinal de que o
orçamento de 4 GB esteja sob pressão real.
