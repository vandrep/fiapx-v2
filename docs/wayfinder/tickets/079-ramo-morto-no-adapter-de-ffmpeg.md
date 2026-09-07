# Ramo morto no adapter de ffmpeg depois do 066

- id: 079
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado dos dois eixos da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 066](066-middle-man-e-bandeira-no-adapter-de-ffmpeg.md) removeu o parâmetro-bandeira
`capturarStdout` do `FfmpegExtracaoDeFramesAdapter`. Com ele fora, `executar` sempre faz
`Files.readString(stdoutArquivo)` antes de montar o `ResultadoDoProcesso`, então `stdout()`
nunca volta `null`.

Dois trechos que existiam para o caso `null` ficaram inalcançáveis:

- `catch (NumberFormatException | NullPointerException erro)` em torno de
  `duracaoBruta.stdout().trim()` — o `NullPointerException` não pode mais acontecer ali.
- `if (streamDeVideo.stdout() == null || streamDeVideo.stdout().isBlank())` — o primeiro termo
  é sempre falso.

O ticket é de forma, não de comportamento: a classificação de falha por exit code, que o 066
exigiu preservar idêntica, não muda. O que sai é código que sugere um estado que a classe não
produz mais, e que a próxima pessoa a ler o adapter precisa provar impossível sozinha.

## O que entregar

Os dois ramos mortos fora, e a leitura do stdout expressa como o que ela é hoje: sempre
presente, possivelmente vazio. `isBlank()` continua sendo a guarda real do stream ausente —
`ffprobe` que não acha stream de vídeo sai com código zero e stdout vazio, e é isso que vira
`SEM_FLUXO_DE_VIDEO`.

## Critérios de aceite

- [ ] Nenhum teste de `null` sobre `stdout()` no adapter
- [ ] `NullPointerException` não aparece mais em `catch` que não possa recebê-lo
- [ ] A classificação por exit code e por stdout vazio sai idêntica
- [ ] `./mvnw test` verde a partir da raiz, com `ffmpeg`/`ffprobe` no `PATH`
