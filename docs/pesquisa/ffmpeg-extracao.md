<!-- label: wayfinder:research -->
# Pesquisa: como extrair frames no serviço `extracao`

- Ticket: [`006-ffmpeg-extracao`](../wayfinder/tickets/006-ffmpeg-extracao.md)
- Data: 2026-08-20
- Contexto: Quarkus 3.31.3, Java 21, stack reativa, worker RabbitMQ, alvo Docker Compose

Método: fontes primárias (manual e código-fonte do FFmpeg, javadoc do JDK, guias oficiais
Quarkus/SmallRye, POMs e artefatos no Maven Central, imagens oficiais) **mais medição
local**. Os números marcados como `[medido]` foram obtidos nesta máquina com
`ffmpeg 7.1.5-0+deb13u1` (Debian 13) e Docker; os comandos estão reproduzidos ao lado de
cada resultado. Nada aqui vem de blog.

---

## 1. Resumo executivo

| Pergunta do ticket | Resposta curta |
|---|---|
| Processo externo ou biblioteca? | **Processo externo** (`ProcessBuilder` + binário `ffmpeg`) |
| Imagem base | `eclipse-temurin:21-jre-alpine` + `apk add --no-cache ffmpeg` — **467 MB** `[medido]` |
| Como não bloquear o event loop | `@Blocking("extracao-pool")` + `smallrye.messaging.worker.extracao-pool.max-concurrency` |
| Progresso confiável | `-progress pipe:1 -nostats` no **stdout**, log em arquivo no **stderr** |
| Transitório vs. permanente | **Não confiar no exit code sozinho**; classificar por exit code *e* pela última linha do stderr |
| Custo | 5 min de 720p → ~7 s, pico de 211 MB RSS, 300 PNGs, 55 MB `[medido]` |
| Risco de vídeos longos | 1 h a 1 fps = 3600 frames = **0,65 GB a 4,5 GB** de PNG `[medido]` |

---

## 2. O comando

O projeto original executa `ffmpeg -i <video> -vf fps=1 -y <dir>/frame_%04d.png`
(`docs/referencia/referencia/projeto-original/main.go`, função `processVideo`). O filtro
está documentado no manual de filtros:

> **fps** — "Change the frame rate by interpolating/dropping frames as necessary."
> Opção `fps`: "Set the output frame rate; default is 25."
> — <https://ffmpeg.org/ffmpeg-filters.html>

O comando equivalente recomendado para o worker acrescenta cinco opções, todas do manual
oficial (<https://ffmpeg.org/ffmpeg.html>):

```
ffmpeg -hide_banner -nostdin -loglevel +level+repeat:error -xerror \
       -nostats -progress pipe:1 \
       -i <entrada> -vf fps=1 -y <saida>/frame_%04d.png
```

| Opção | Justificativa (citação do manual) |
|---|---|
| `-nostdin` | "Disabling interaction on standard input is useful, for example, if ffmpeg is in the background process group." Sem isso, um ffmpeg herdando stdin pode travar esperando entrada. |
| `-hide_banner` | "Suppress printing banner. All FFmpeg tools will normally show a copyright notice, build options and library versions." Tira ~20 linhas de ruído do stderr. |
| `-loglevel [flags+]loglevel` | Flags disponíveis incluem `'level'` — "Indicates that log output should add a `[level]` prefix to each message line" — e `'repeat'` — "Indicates that repeated log output should not be compressed". O prefixo `[level]` é o que torna o stderr **parseável por máquina** (`[error]`, `[fatal]`). |
| `-nostats` | `-stats` "is on by default, to explicitly disable it you need to specify `-nostats`". Necessário porque `-stats` polui o stderr com a barra de progresso. |
| `-progress url` | "Send program-friendly progress information to url. Progress information is written periodically and at the end of the encoding process. It is made of 'key=value' lines." Período controlado por `-stats_period` (default 0,5 s). |
| `-xerror` | **Crítico** — ver §5.2. Sem ele, vídeo parcialmente corrompido sai com código 0. |
| `-y` | "Overwrite output files without asking." |

---

## 3. Exit code do ffmpeg: o que o código-fonte realmente faz

Esta é a descoberta que muda o desenho da política de retry. O manual **não documenta**
exit codes; a resposta está em `fftools/ffmpeg.c`, função `main()`
(<https://github.com/FFmpeg/FFmpeg/blob/master/fftools/ffmpeg.c>):

```c
    ret = received_nb_signals                 ? 255 :
          (ret == FFMPEG_ERROR_RATE_EXCEEDED) ?  69 : ret;

finish:
    if (ret == AVERROR_EXIT)
        ret = 0;
    ...
    return ret;
```

`ret` é um `AVERROR`, isto é, um **inteiro negativo**. `libavutil/error.h`:

```c
#define AVERROR(e) (-(e))   ///< Returns a negative error code from a POSIX error code
#define AVERROR_INVALIDDATA        FFERRTAG( 'I','N','D','A') ///< Invalid data found when processing input
#define AVERROR_DECODER_NOT_FOUND  FFERRTAG(0xF8,'D','E','C')
#define AVERROR_DEMUXER_NOT_FOUND  FFERRTAG(0xF8,'D','E','M')
#define AVERROR_ENCODER_NOT_FOUND  FFERRTAG(0xF8,'E','N','C')
```

Como POSIX trunca o status de saída em 8 bits, o exit code observado é
`AVERROR & 0xFF`. Isso foi confirmado experimentalmente `[medido]`:

| Cenário reproduzido | Exit | Derivação | Última linha do stderr |
|---|---|---|---|
| Arquivo de entrada inexistente | **254** | `-ENOENT` = -2 → `0x100-2` | `Error opening input files: No such file or directory` |
| Bytes aleatórios / texto / MP4 sem `moov` | **183** | `AVERROR_INVALIDDATA` → low byte `0x49` → `0xB7` | `Error opening input files: Invalid data found when processing input` |
| Saída sem permissão de escrita | **251** | `-EIO` = -5 | `Error muxing a packet` (precedida de `Could not open file`) |
| Encoder/decoder inexistente | **8** | `FFERRTAG(0xF8,…)` → `0x100-0xF8` | `Unknown encoder 'x'` |
| Arquivo só com áudio (nenhum stream de vídeo) | **234** | `-EINVAL` = -22 | `Error opening output files: Invalid argument` |
| Disco cheio (`--tmpfs /out:size=2m`) | **228** | `-ENOSPC` = -28 | `Error submitting a packet to the muxer: No space left on device` |
| `SIGKILL` externo (proxy do OOM killer do cgroup) | **137** | `128 + 9`, convenção do kernel/JVM | (nada) |
| `SIGTERM` | **255** | `received_nb_signals ? 255` no fonte | (nada) |
| Taxa de erro excedida (`-max_error_rate`) | **69** | literal no fonte | — |

### 3.1 Por que o exit code sozinho não basta

Três problemas, todos derivados do fonte acima:

1. **Colisão.** `AVERROR_DECODER_NOT_FOUND`, `AVERROR_DEMUXER_NOT_FOUND`,
   `AVERROR_ENCODER_NOT_FOUND` e `AVERROR_PROTOCOL_NOT_FOUND` começam todos com o byte
   `0xF8` e portanto **colapsam no mesmo exit code 8**. Não dá para distinguir
   "codec não suportado" de "protocolo não suportado" pelo número.
2. **Risco de falso sucesso.** Qualquer `AVERROR` cujo byte baixo seja `0x00` reportaria
   exit 0. É improvável, mas é uma consequência estrutural do `return ret` com valor de
   32 bits.
3. **Falso sucesso garantido no caso mais importante** — ver §5.2.

**Conclusão: a classificação tem de combinar exit code com a última linha do stderr.**
O ffmpeg é consistente ao emitir a linha final `Error opening input files: <av_strerror>`
/ `Error opening output files: <av_strerror>` / `Conversion failed!`, e essa string é a
tradução textual do mesmo `AVERROR` — sem a truncagem de 8 bits.

---

## 4. Processo externo: como executar em Java sem se machucar

### 4.1 Deadlock de pipes

O javadoc de `java.lang.Process` (Java 21) é explícito:

> "Because some native platforms only provide limited buffer size for standard input and
> output streams, failure to promptly write the input stream or read the output stream of
> the process may cause the process to block, or even deadlock."
> — <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html>

O `main.go` original usa `cmd.CombinedOutput()`, que drena tudo em memória. Em Java o
equivalente ingênuo (ler `getInputStream()` e depois `getErrorStream()` na mesma thread)
**trava** assim que um dos pipes enche.

A solução que evita threads de drenagem por completo:

- **stdout** → `Redirect.PIPE`, lido linha a linha pela própria thread worker (é onde sai
  o `-progress`, e é ele que dá o ritmo do laço).
- **stderr** → `ProcessBuilder.redirectError(File)` para um arquivo temporário. O SO
  escreve direto no arquivo, sem pipe, logo **não há como encher buffer**. Com
  `-loglevel error` o arquivo fica na casa de centenas de bytes; em caso de falha,
  lê-se o arquivo inteiro para classificar.
- **stdin** → `redirectInput(Redirect.from(new File("/dev/null")))`, redundante com
  `-nostdin` mas barato.

Não usar `redirectErrorStream(true)`: isso mistura o `-progress` (estruturado) com o log
(livre) no mesmo pipe e destrói o parsing.

### 4.2 Formato do `-progress` `[medido]`

`ffmpeg … -nostats -progress pipe:1 -i real720.mp4 -vf fps=1 out/f_%04d.png` emitiu no
stdout, a cada ~0,5 s, blocos `chave=valor` terminados por `progress=continue` (ou
`progress=end` no último):

```
frame=38
fps=37.99
out_time_us=38000000
out_time_ms=38000000
out_time=00:00:38.000000
dup_frames=0
drop_frames=0
speed=  38x
progress=continue
```

Combinado com a duração obtida antes por `ffprobe`, `out_time_us / duracao_us` dá um
percentual honesto de progresso, sem regex sobre texto livre.

### 4.3 Pré-validação com `ffprobe` `[medido]`

```
ffprobe -v error -select_streams v:0 \
        -show_entries stream=codec_name,width,height,nb_frames \
        -show_entries format=duration,format_name -of json <arquivo>
```

- Arquivo válido → rc=0, JSON com `duration`, `codec_name`, `nb_frames`.
- Lixo binário → **rc=1** e `Invalid data found when processing input`.
- Arquivo só com áudio → **rc=0** mas `"streams": []` (o ffmpeg só falha depois, com 234).

Vale a pena rodar o `ffprobe` antes: ele detecta duas classes de falha **permanente** em
milissegundos (arquivo ilegível, ausência de stream de vídeo) e ainda entrega a duração
necessária para o progresso e para o limite operacional de frames.

### 4.4 Timeout e cancelamento

`Process.waitFor(long, TimeUnit)` — "Returns: `true` if the process has exited and `false`
if the waiting time elapsed before the process has exited." Em `false`, chamar
`destroy()` e, se `isAlive()` persistir, `destroyForcibly()` (o javadoc avisa: "The
process may not terminate immediately… This method may be chained to `waitFor()` if
needed"). Um timeout estourado é **falha transitória** (a máquina pode estar sob carga).

`Process.onExit()` devolve um `CompletableFuture<Process>` e existe para quem quer compor
assincronamente — "Processes returned from `ProcessBuilder.start()` override the default
implementation to provide an efficient mechanism to wait for process exit". Como aqui já
estamos numa worker thread (§4.5), `waitFor(timeout)` é mais simples e igualmente correto.

### 4.5 Onde a execução vive: não bloquear o event loop

O guia oficial do Quarkus para RabbitMQ dá o caminho:

> "`io.smallrye.reactive.messaging.annotations.Blocking` provides more fine-grained tuning
> such as the worker pool to use and whether it preserves the order";
> "`io.smallrye.common.annotation.Blocking` uses the default worker pool and preserves the order."
> — <https://quarkus.io/guides/rabbitmq-reference>

E a documentação do SmallRye detalha o pool nomeado e o limite de concorrência:

> `@Blocking("my-custom-pool")` … `smallrye.messaging.worker.my-custom-pool.max-concurrency=3`
> — <https://smallrye.io/smallrye-reactive-messaging/latest/concepts/blocking/>

Desenho recomendado:

```java
@Incoming("extracao-comandos")
@Blocking(value = "extracao-pool", ordered = false)
public void processar(ComandoExtracao cmd) { … }
```

```properties
smallrye.messaging.worker.extracao-pool.max-concurrency=2
```

O `max-concurrency` é o item mais importante da configuração: **ele é o teto de processos
`ffmpeg` simultâneos**. Sem ele, um pico na fila derruba o container por memória ou disco.
`ordered = false` é aceitável porque cada Vídeo é independente (o `videos` é dono do
estado, o `extracao` é worker sem estado — `CONTEXT.md`).

**Não usar `@RunOnVirtualThread` aqui.** O guia de virtual threads do Quarkus é direto:

> "Virtual threads are not useful for long computations (CPU-bound workload). It is
> useless and counterproductive."; a virtual thread "monopolizes the carrier thread on
> which it is mounted"; e em Java 21–23 há pinning "when a virtual thread performs a
> blocking operation inside a `synchronized` block or method" ou em código nativo.
> — <https://quarkus.io/guides/virtual-threads>

Ainda que esperar por um processo externo seja tecnicamente I/O, virtual threads não
limitam concorrência — e limitar concorrência é exatamente o que precisamos. O worker pool
nomeado resolve os dois problemas de uma vez.

---

## 5. Classificar transitório vs. permanente

### 5.1 A tabela de decisão

Ordem de avaliação: (1) `ffprobe` falhou? (2) exit code; (3) última linha do stderr.

| Sinal | Classe | Racional |
|---|---|---|
| `ffprobe` rc≠0 | **Permanente** | O arquivo não é demuxável. Nenhuma tentativa muda isso. |
| `ffprobe` rc=0 mas `streams: []` | **Permanente** | Não há stream de vídeo — nada a extrair. |
| exit **183** (`AVERROR_INVALIDDATA`) | **Permanente** | Dado inválido. Arquivo corrompido / não é vídeo. |
| exit **8** + stderr contém `Unknown encoder` / `Unknown decoder` / `Decoder … not found` | **Permanente** | Codec não suportado por este build de ffmpeg. |
| exit **234** (`EINVAL`) + `Output file does not contain any stream` | **Permanente** | Idem "sem stream de vídeo". |
| exit **254** (`ENOENT`) | **Permanente** *(com ressalva)* | O objeto baixado do MinIO sumiu; retentar o mesmo download tende a falhar igual. Se o download for parte da mesma tentativa, tratar como transitório. |
| exit **228** (`ENOSPC`) | **Transitório** | Disco cheio; limpeza/outro pod libera espaço. |
| exit **251** (`EIO`) | **Transitório** | I/O do volume. |
| exit **244** (`ENOMEM`) | **Transitório** | Memória. |
| exit **137** | **Transitório** | `128+SIGKILL` — na prática, OOM killer do cgroup. |
| exit **255** | **Transitório** | `received_nb_signals` no fonte: `SIGTERM`/shutdown do container no meio do trabalho. |
| exit **69** | **Transitório** | `FFMPEG_ERROR_RATE_EXCEEDED`. |
| timeout do `waitFor` | **Transitório** | Máquina sob carga. |
| Exceção de I/O do lado Java (MinIO, disco, RabbitMQ) | **Transitório** | Nada a ver com o conteúdo do vídeo. |
| **Qualquer outro exit ≠ 0** | **Transitório** | Falha fechada e conservadora: 3 tentativas custam pouco; classificar errado como permanente perde o vídeo em definitivo. |

Regra de ouro para o desempate: **a falha é permanente quando é uma propriedade do
conteúdo do Vídeo; é transitória quando é uma propriedade do ambiente.** O exit code diz
qual das duas na maioria dos casos; o stderr desempata o exit 8 e serve de evidência na
mensagem de erro publicada.

### 5.2 A armadilha: sem `-xerror` o ffmpeg mente `[medido]`

Um MP4 com `moov` no início (`-movflags +faststart`) truncado a 60% dos bytes:

```
ffmpeg -hide_banner -nostdin -loglevel error -i fs_trunc.mp4 -vf fps=1 f8/o_%04d.png
→ exit=0, 11 frames escritos
  stderr: [h264 @ …] Invalid NAL unit size (62 > 8).
          [mov,mp4,… @ …] stream 0, offset 0xe887: partial file
```

O mesmo comando **com `-xerror`**:

```
→ exit=183, 11 frames escritos
  stderr: [in#0/mov,… @ …] corrupt input packet in stream 0
          [in#0/mov,… @ …] Task finished with error code: -1094995529 (Invalid data found when processing input)
```

Sem `-xerror`, o worker entregaria um Pacote com 11 frames de um vídeo de 20 s e marcaria
o Vídeo como `CONCLUIDO`. É a pior falha possível: silenciosa e visível só para o usuário.
Note também `-1094995529` = `AVERROR_INVALIDDATA` em 32 bits — a mesma constante cujo byte
baixo vira 183.

Consequência de desenho: além de `-xerror`, o worker deve **conferir a contagem de frames
produzidos contra a esperada** (`duracao_segundos` do `ffprobe`, arredondada). Uma
divergência grande com exit 0 é falha permanente de conteúdo. O `main.go` original já faz
meia verificação (`len(frames) == 0` → erro), mas não a completa.

### 5.3 Encaixe com a política decidida

O mapa fixou "Retry 3x com backoff para falhas transitórias; falha permanente não gasta
retry; esgotado vai para DLQ". Isso mapeia diretamente em SmallRye Fault Tolerance
(<https://quarkus.io/guides/smallrye-fault-tolerance>), que expõe
`@Retry` de `org.eclipse.microprofile.faulttolerance` com `maxRetries` (default 3),
`retryOn` e `abortOn`, mais `@ExponentialBackoff` (`factor` default 2, `max-delay` default
1 min). Modelando duas exceções — `FalhaTransitoriaException` e
`FalhaPermanenteException` — a política vira declaração:

```java
@Retry(maxRetries = 3, retryOn = FalhaTransitoriaException.class,
       abortOn = FalhaPermanenteException.class)
@ExponentialBackoff
```

(A escolha entre retry em processo e redelivery do RabbitMQ é do ticket de política de
retry, não deste. O insumo que este ticket entrega é a classificação da §5.1.)

---

## 6. Custo de tempo, memória e disco `[medido]`

Vídeo sintético `testsrc2`, 1280×720, 30 fps, 300 s, H.264 (102 MB). Máquina local.

| Operação | Tempo | Pico RSS | Saída |
|---|---|---|---|
| `-vf fps=1` → PNG | **7,1 s** | **211 MB** | 300 arquivos, **55 MB** (~185 KB/frame) |
| `-vf fps=1 -q:v 2` → JPG | **6,7 s** | — | 300 arquivos, **21 MB** (~70 KB/frame) |

Pior caso de entropia (vídeo de ruído puro, 720p — limite superior para PNG, que é sem
perdas):

| Formato | Média por frame |
|---|---|
| PNG | **1260 KB** |
| JPG `-q:v 2` | **648 KB** |

Ou seja, o custo de CPU é dominado pela **decodificação**, não pelo fps=1: 300 s de vídeo
em ~7 s dá ~43× tempo real numa máquina de desenvolvimento. Um vídeo de 10 min processa
em ~15 s. O gargalo real do serviço é **disco e rede**, não CPU.

### 6.1 O que fps=1 faz com vídeos longos

Frames = duração em segundos (medido: vídeo de 20 s → exatamente 20 PNGs; 300 s → 300).

| Duração | Frames | PNG 720p (típico → pior caso) | JPG q=2 |
|---|---|---|---|
| 1 min | 60 | 11 MB → 74 MB | 4 MB |
| 10 min | 600 | 110 MB → 740 MB | 42 MB |
| 1 h | **3600** | **0,65 GB → 4,4 GB** | 0,25 GB |

Isto confirma o item "Limites operacionais" listado como não-especificado no mapa. Três
recomendações concretas, todas apoiadas em medição:

1. **Limitar a duração aceita** (o `ffprobe` já dá a duração antes de gastar CPU) — um
   teto de 10 min mantém o Pacote abaixo de ~1 GB no pior caso e a extração abaixo de 20 s.
2. **Considerar JPG em vez de PNG** — 3× menor em conteúdo típico, 2× no pior caso, mesmo
   tempo. O enunciado pede "imagens"; não exige PNG. (Decisão de produto, não desta pesquisa.)
3. **Zipar com `STORED`, não `DEFLATED`.** Medido: 300 PNGs de 36,9 MB comprimidos com
   `ZIP_DEFLATED` deram **36,9 MB** — ganho zero, porque PNG já é deflate. O `main.go`
   original usa `zip.Deflate`; gastar CPU nisso é desperdício puro. `java.util.zip.ZipEntry`
   com `ZipOutputStream.setMethod(ZipOutputStream.STORED)` (ou nível 0) escreve na
   velocidade do disco. Com JPG a conclusão é a mesma.

Além disso, o worker precisa de espaço em `/tmp` para **vídeo + frames + zip
simultaneamente**. Para um teto de 10 min: ~200 MB (vídeo) + até 740 MB (frames) + 740 MB
(zip) ≈ 1,7 GB. Isso é um `tmpfs`/volume dimensionado no Compose, não uma suposição.

---

## 7. Imagem Docker: números reais `[medido]`

Todas as imagens abaixo foram construídas e medidas nesta máquina com `docker build` +
`docker images`. Tamanhos são "descompactado no host", que é a métrica que o
`docker images` reporta.

| Imagem | Tamanho | Versão do ffmpeg | Observação |
|---|---|---|---|
| `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.21` | 534 MB | **nenhuma** | Base **default do Quarkus** para Java 21 |
| **`eclipse-temurin:21-jre-alpine` + `apk add ffmpeg`** | **467 MB** | 6.1.2 | **recomendada** |
| `alpine:3.21` + `openjdk21-jre-headless` + `ffmpeg` | 458 MB | 6.1.2 | JRE da Alpine, menos suportado que Temurin |
| `eclipse-temurin:21-jre-noble` + `apt install ffmpeg` | **1,02 GB** | 6.1.x | +614 MB só de ffmpeg e suas dependências X11 |
| `debian:trixie-slim` + `openjdk-21-jre-headless` + `ffmpeg` | 1,03 GB | 7.1 | idem |
| `ubi9/openjdk-21-runtime` + binário estático BtbN (gpl) | 945 MB | master | binários de **147 MB cada** |
| `ubi9/openjdk-21-runtime` + estático John Van Sickle | 754 MB | 7.0.2 (2024) | binários de **80 MB cada**; build desatualizado |

Baselines: `alpine:3.21` = 12,2 MB; `eclipse-temurin:21-jre-alpine` = 286 MB;
`eclipse-temurin:21-jre-noble` = 406 MB; `alpine + ffmpeg` (sem JRE) = 189 MB.

O guia oficial confirma o default do Quarkus:

> Para Java 21–24: `"registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24"` (propriedade
> `quarkus.jib.base-jvm-image`).
> — <https://quarkus.io/guides/container-image>

### 7.1 Por que não dá para ficar na base default do Quarkus

**Não existe pacote `ffmpeg` nos repositórios do RHEL/UBI** (é fornecido por RPM Fusion,
terceiro). As duas saídas — instalar RPM Fusion ou copiar um binário estático — custam
mais do que trocar de base: as variantes com binário estático medidas acima ficaram em
754 MB e 945 MB, contra 467 MB da Alpine. A página oficial de download do FFmpeg confirma
que binários não vêm da upstream:

> "FFmpeg only provides source code" — <https://ffmpeg.org/download.html>

A única fonte de builds Linux linkada de lá hoje é
<https://github.com/BtbN/FFmpeg-Builds/releases> (os builds estáticos GPL do BtbN têm
128 MB comprimidos, 147 MB por binário descompactado — medido via API do GitHub e
inspeção da imagem). John Van Sickle **não é mais linkado** da página oficial e o
`ffmpeg-release-amd64-static` corrente ainda é 7.0.2 de 2024.

Portanto: trocar a base para Alpine e usar o pacote da distro é a opção menor, mais nova e
mais simples de manter. Concretamente, isso significa **não** usar o `Dockerfile.jvm`
gerado pelo Quarkus como está para o `extracao`, e sim uma variante com `FROM` trocado.

### 7.2 Ressalvas da Alpine

- Alpine 3.21 e 3.22 empacotam **ffmpeg 6.1.2** — mais velho que o 7.1 do Debian trixie.
  Irrelevante para decodificar H.264/HEVC/VP9 e escrever PNG; o filtro `fps` e as opções
  `-progress`/`-xerror` existem desde muito antes. O comportamento de exit code descrito
  na §3 é o mesmo (foi validado na imagem Alpine: o teste de `ENOSPC` que deu 228 rodou
  dentro de `fx-alpine-jre`).
- Alpine usa **musl**. Em modo **JVM** isso é irrelevante (a JVM Temurin Alpine é
  compilada para musl). Só seria um problema em native image, que está fora do caminho
  aqui.
- Como só o `extracao` precisa de ffmpeg, `videos` e `notificacao` podem continuar na base
  default do Quarkus. Não há razão para uniformizar bases à custa de 470 MB extras em cada
  um.

---

## 8. A alternativa: JavaCV / bytedeco

### 8.1 O que se paga em bytes

Sizes obtidos por `HEAD` no Maven Central `[medido]`:

| Artefato | Tamanho |
|---|---|
| `org.bytedeco:javacv:1.5.14` | 0,4 MB |
| `org.bytedeco:javacpp:1.5.14` | 0,5 MB |
| `org.bytedeco:ffmpeg:8.1.2-1.5.14` (API Java) | 0,3 MB |
| `org.bytedeco:ffmpeg:8.1.2-1.5.14:linux-x86_64` (natives LGPL) | **25,7 MB** |
| `…:linux-x86_64-gpl` | 29,7 MB |
| `…:linux-arm64` | 25,1 MB |

Escopo mínimo funcional ≈ **28 MB** de jars — genuinamente menor que os ~180 MB que o
ffmpeg da Alpine acrescenta. **Mas isso só vale se o escopo for cuidadosamente restrito.**
O POM de `javacv-platform:1.5.14` declara dependência de `opencv-platform`,
`tesseract-platform`, `openblas-platform`, `librealsense2-platform`, `libfreenect2-platform`,
`artoolkitplus-platform`, `flycapture-platform`, `videoinput-platform` e
`ffmpeg-platform-gpl` — e os artefatos `-platform` trazem **todos os classifiers de todos
os SOs**. Usar `javacv-platform` sem `-Djavacpp.platform=linux-x86_64` puxa vários GB. O
README oficial confirma o mecanismo de contenção:

> "set the `javacpp.platform` system property (via the `-D` command line option)"
> — <https://github.com/bytedeco/javacv/blob/master/README.md>

### 8.2 O que se paga em desempenho `[medido]`

Escrevi um extrator equivalente com `FFmpegFrameGrabber` + `Java2DFrameConverter` +
`ImageIO.write(…, "png", …)` e rodei sobre **o mesmo arquivo** de 5 min/720p usado na §6
(via jbang, `org.bytedeco:javacv:1.5.14` + `ffmpeg:8.1.2-1.5.14:linux-x86_64`):

| Abordagem | Tempo | Heap/RSS | Frames |
|---|---|---|---|
| `ffmpeg` CLI (`-vf fps=1`) | **7,1 s** | 211 MB RSS | 300 |
| JavaCV `FFmpegFrameGrabber` + `ImageIO` | **25,2 s** | heap cresceu para **1008 MB** | 300 |

**3,5× mais lento e ~5× mais memória**, para produzir exatamente o mesmo resultado. A
razão é estrutural: o CLI mantém o pixel data em C do decoder ao encoder PNG; a rota
JavaCV copia cada frame para um `BufferedImage` na heap e usa o encoder PNG puro-Java do
`ImageIO`. Isso importa muito num worker que também vai concorrer com o Compose inteiro na
máquina do avaliador.

Também não existe atalho: `FFmpegFrameGrabber` não tem "extraia a 1 fps". É preciso ou
decodificar tudo e selecionar por timestamp (o que fiz) ou montar um `FFmpegFrameFilter`
com a string `"fps=1"` — isto é, **reimplementar em Java a linha de comando que se queria
evitar**, com uma API muito maior.

### 8.3 O que se paga em atrito operacional

- **JavaCPP extrai os `.so` em runtime.** Depois da execução, `~/.javacpp/cache` continha
  **65 MB** de bibliotecas extraídas dos jars `[medido]`. Isso exige um filesystem
  gravável no container e um cache dir configurado explicitamente
  (`-Dorg.bytedeco.javacpp.cachedir`) — atrito adicional em imagem read-only e um custo de
  latência no primeiro processamento após cada start.
- **Acesso nativo restrito.** Na execução aparece:
  `WARNING: java.lang.System::load has been called by org.bytedeco.javacpp.Loader in an
  unnamed module … Restricted methods will be blocked in a future release unless native
  access is enabled`. Já é preciso pensar em `--enable-native-access`; é dívida futura.
- **Sem extensão Quarkus oficial.** Funciona em modo JVM como jar comum, mas não há
  suporte de native image nem integração de dev services.
- **Falhas viram exceções, não exit codes.** A classificação da §5.1, que é baseada em
  exit code + stderr, teria de ser reescrita sobre `FFmpegFrameGrabber.Exception` e
  strings de `av_strerror`. É trabalho equivalente, sem ganho.

### 8.4 O que se ganha

Um item só, mas real: **elimina a dependência de um binário externo na imagem**, e portanto
o acoplamento entre a versão do ffmpeg e a distro base. Se a imagem base tivesse que ser a
`ubi9` do Quarkus por política, o JavaCV seria a resposta certa (28 MB de jars contra
220–410 MB de binário estático). Não é o nosso caso — nada nas restrições do mapa obriga a
base UBI.

---

## 9. RECOMENDAÇÃO

> **Usar processo externo: `ProcessBuilder` invocando o binário `ffmpeg`, numa imagem
> `eclipse-temurin:21-jre-alpine` com `apk add --no-cache ffmpeg` (467 MB `[medido]`),
> executado sob `@Blocking("extracao-pool", ordered = false)` com `max-concurrency`
> explícito.**

Justificativa, na ordem em que os argumentos pesam:

1. **É 3,5× mais rápido e usa 5× menos memória**, medido no mesmo vídeo, com a mesma
   saída (§8.2). Num trabalho que é 100% processamento de mídia, isso é *a* característica
   do serviço, não um detalhe.
2. **A classificação de falha — que é o insumo direto da política de retry já decidida —
   cai de graça.** Exit code + última linha do stderr dão a tabela da §5.1 sem nenhuma
   camada de adaptação. Pela rota JavaCV, seria preciso reconstruir a mesma taxonomia
   sobre exceções e strings de `av_strerror`, com mais código e menos evidência.
3. **É o mesmo desenho do projeto original**, que já está validado no domínio
   (`exec.Command("ffmpeg", "-i", …, "-vf", "fps=1", …)`). Com ~5,5 semanas e uma pessoa,
   traduzir um desenho conhecido para `ProcessBuilder` é uma tarde; aprender a API do
   JavaCV, dimensionar o `javacpp.platform`, resolver o cache de natives e o
   `--enable-native-access` é uma semana com riscos que só aparecem em runtime.
4. **O custo de imagem é aceitável e conhecido**: 467 MB, *menos* que os 534 MB da base
   default do Quarkus que os outros dois serviços já vão usar. O argumento "JavaCV economiza
   MB" só se sustenta contra a hipótese de manter a base UBI, que não é obrigatória.
5. **Isolamento de falha.** Um vídeo malformado que faça o decoder do FFmpeg estourar mata
   um processo filho e devolve um exit code classificável. Na rota JavaCV, o mesmo bug
   acontece dentro da JVM do worker — um `SIGSEGV` em código nativo derruba o container e
   todas as mensagens em voo, não só a que causou o problema. Para um worker que precisa
   ser resiliente por requisito do enunciado, isto é decisivo.

Contrapeso honesto: o processo externo torna o serviço `extracao` dependente da imagem
Docker de um jeito que testes unitários não pegam — `ffmpeg` precisa existir no `PATH`
tanto no container quanto na máquina de quem roda os testes. Mitigação: encapsular a
invocação atrás de uma porta de saída (`ExtratorDeFrames` no `core`, adapter em
`infrastructure`, conforme as regras de camada do template), testar o `core` com um duplo,
e cobrir o adapter real apenas no script de smoke ponta-a-ponta que o mapa já prevê. Um
health check que executa `ffmpeg -version` no startup transforma "binário ausente" em
falha de boot visível em vez de falha na primeira mensagem.

### 9.1 Checklist de implementação derivado desta pesquisa

- [ ] `Dockerfile.extracao`: `FROM eclipse-temurin:21-jre-alpine` + `RUN apk add --no-cache ffmpeg`
- [ ] Health check que roda `ffmpeg -version` (falha rápida se o binário sumir da imagem)
- [ ] `ffprobe` antes do `ffmpeg`: valida o arquivo, rejeita "sem stream de vídeo", obtém a duração
- [ ] Recusar vídeos acima do teto de duração escolhido (§6.1), antes de gastar CPU
- [ ] Comando com `-nostdin -hide_banner -loglevel +level+repeat:error -xerror -nostats -progress pipe:1`
- [ ] stderr para arquivo temporário (`redirectError`), stdout em PIPE para o `-progress`
- [ ] `waitFor(timeout)` + `destroyForcibly()`; timeout = falha transitória
- [ ] Conferir contagem de frames produzidos contra a esperada, mesmo com exit 0
- [ ] Classificar a falha pela tabela da §5.1 em `FalhaTransitoria` / `FalhaPermanente`
- [ ] ZIP com `STORED`, não `DEFLATED`
- [ ] `@Blocking("extracao-pool", ordered = false)` + `smallrye.messaging.worker.extracao-pool.max-concurrency`
- [ ] Volume/`tmpfs` dimensionado para vídeo + frames + zip simultâneos
- [ ] Limpeza do diretório temporário em `finally`, inclusive no caminho de falha

---

## 10. Fontes

Primárias, todas consultadas em 2026-08-20:

- FFmpeg — manual do `ffmpeg`: <https://ffmpeg.org/ffmpeg.html>
- FFmpeg — manual de filtros (`fps`): <https://ffmpeg.org/ffmpeg-filters.html>
- FFmpeg — página de download (política de binários, builds Linux): <https://ffmpeg.org/download.html>
- FFmpeg — `fftools/ffmpeg.c`, função `main()`: <https://github.com/FFmpeg/FFmpeg/blob/master/fftools/ffmpeg.c>
- FFmpeg — `libavutil/error.h`: <https://github.com/FFmpeg/FFmpeg/blob/master/libavutil/error.h>
- Oracle — javadoc `java.lang.Process` (Java 21): <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Process.html>
- Quarkus — RabbitMQ Reference (`@Blocking`, acknowledgement, failure-strategy): <https://quarkus.io/guides/rabbitmq-reference>
- Quarkus — Virtual Threads: <https://quarkus.io/guides/virtual-threads>
- Quarkus — Container Images (base JVM default): <https://quarkus.io/guides/container-image>
- Quarkus — SmallRye Fault Tolerance (`@Retry`, `@ExponentialBackoff`): <https://quarkus.io/guides/smallrye-fault-tolerance>
- SmallRye Reactive Messaging — Blocking processing: <https://smallrye.io/smallrye-reactive-messaging/latest/concepts/blocking/>
- JavaCV — README oficial: <https://github.com/bytedeco/javacv/blob/master/README.md>
- Maven Central — POM de `javacv-platform:1.5.14` e tamanhos dos artefatos `org.bytedeco:*`
- BtbN/FFmpeg-Builds — API de releases do GitHub (tamanhos dos tarballs)
- Imagens oficiais: `eclipse-temurin`, `alpine`, `debian`, `registry.access.redhat.com/ubi9/openjdk-21-runtime`
- Projeto original: `docs/referencia/referencia/projeto-original/main.go`

Medições locais: `ffmpeg 7.1.5-0+deb13u1` (Debian 13, x86_64), Docker, Temurin 25 (jbang)
para o benchmark JavaCV.
