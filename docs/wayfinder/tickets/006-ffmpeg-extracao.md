# Como extrair frames a partir do serviço extracao

- id: 006
- label: wayfinder:research
- status: fechado
- assignee: agente de pesquisa (sessao de 2026-08-20)
- bloqueado-por:

## Question

O projeto original faz `exec.Command("ffmpeg", "-i", video, "-vf", "fps=1", pattern)`. Em
Java há mais de um caminho, e a escolha amarra a imagem Docker do `extracao`.

Investigar e comparar:

- **Processo externo**: `ProcessBuilder` chamando o binário `ffmpeg`. Qual imagem base
  para um runner Quarkus JVM com ffmpeg instalado? Tamanho resultante? Como capturar
  progresso e erro de forma confiável? Como não bloquear o event loop reativo (worker
  thread / `@Blocking`)?
- **Biblioteca Java** (JavaCV/`bytedeco`, ou similar): elimina a dependência de binário
  externo ao custo de dezenas de MB de natives e de uma API bem mais pesada. Vale?
- Como distinguir, na prática, **falha permanente** (arquivo corrompido, codec não
  suportado) de **falha transitória** (I/O, memória) a partir do retorno do ffmpeg — este
  é o insumo direto da política de retry decidida.
- Custo de tempo e memória para um vídeo típico, e o que fps=1 produz para vídeos longos.

Registre os achados em `docs/pesquisa/ffmpeg-extracao.md`. Termine com uma recomendação
explícita entre processo externo e biblioteca, com a justificativa.

## Resolução

Achados completos em [`docs/pesquisa/ffmpeg-extracao.md`](../../pesquisa/ffmpeg-extracao.md).
Baseado em fonte primária (manual e fonte C do FFmpeg, javadoc do JDK 21, guias
Quarkus/SmallRye, POMs do Maven Central) **e em medição local** — ffmpeg e Docker estavam
disponíveis, então as imagens foram construídas e os benchmarks rodados de fato.

**Recomendação: processo externo (`ProcessBuilder` + binário ffmpeg), não JavaCV.** Medido
no mesmo vídeo (5 min, 720p): CLI 7,1 s / 211 MB RSS contra JavaCV 25,2 s / heap de 1008 MB
— 3,5× mais lento e 5× mais memória para saída idêntica. Somam-se: a classificação de falha
sai de graça do exit code; um segfault nativo mata um processo filho em vez do container; e
o JavaCV exige `javacpp.platform` (senão puxa OpenCV/Tesseract/RealSense), cache gravável de
65 MB de `.so` em runtime e `--enable-native-access`.

**Imagem base: `eclipse-temurin:21-jre-alpine` + `apk add --no-cache ffmpeg` = 467 MB**
(medido). Comparativos: `ubi9/openjdk-21-runtime` (base default do Quarkus) = 534 MB e **não
tem ffmpeg nos repositórios**; temurin noble + apt = 1,02 GB; ubi9 + binário estático BtbN =
945 MB.

**Transitório vs permanente**: o exit code do ffmpeg é `AVERROR & 0xFF` (confirmado no
`main()` de `fftools/ffmpeg.c` e reproduzido).

- Permanente: 183 (`INVALIDDATA` — corrompido ou não é vídeo), 8 + `Unknown encoder/decoder`,
  234 + `does not contain any stream`, ou `ffprobe` com rc≠0.
- Transitório: 228 (`ENOSPC`), 251 (`EIO`), 244 (`ENOMEM`), 137 (OOM killer), 255 (`SIGTERM`),
  69, timeout, e **qualquer outro exit ≠ 0** — falha fechada e conservadora.
- O exit 8 colide entre decoder/demuxer/encoder/protocol not found, então ler o stderr é
  obrigatório para desempatar.

**Duas armadilhas:**

- **Sem `-xerror`, um MP4 truncado sai com exit 0** e frames faltando (11 de 20, medido) — o
  worker marcaria `CONCLUIDO` um Pacote incompleto. Além da flag, conferir a contagem de
  frames contra a duração do `ffprobe`.
- **Zipar PNG com deflate dá ganho zero** (36,9 MB → 36,9 MB, medido): o ZIP deve ser
  `STORED`. O `main.go` original usa `zip.Deflate` e gasta CPU à toa.

**Achado operacional**: fps=1 numa hora de 720p gera 3600 frames, de 0,65 GB a 4,4 GB de PNG.
Graduou para o ticket 011.
