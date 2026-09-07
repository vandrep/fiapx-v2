# `falhaPermanente` e o parâmetro-bandeira do adapter de ffmpeg

- id: 066
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7` (smells do baseline de Fowler),
convertido em ticket com aprovação do usuário.

## O problema

Duas formas no `FfmpegExtracaoDeFramesAdapter` custam mais do que rendem:

- **`falhaPermanente(...)` é *Middle Man***: só invoca o construtor da exceção. E nem é a única
  porta — outro ponto do mesmo arquivo constrói a exceção direto, então o leitor precisa
  descobrir que as duas formas são a mesma coisa antes de poder ignorar a diferença.
- **`executar(..., boolean capturarStdout, ...)` é parâmetro-bandeira**, escondido atrás de dois
  wrappers que existem só para não escrever o booleano no sítio de chamada. A bandeira continua
  lá; o que os wrappers compram é o nome, não a separação.

Nenhum dos dois é violação de padrão documentado — são juízo de valor, e o ticket existe porque
o usuário aprovou tratá-los.

## O que entregar

Uma decisão explícita para cada um, e ela pode ser "fica como está" desde que escrita:

- `falhaPermanente` some (chamadores constroem a exceção direto) **ou** passa a ser a única
  porta, aplicada nos dois sítios. As duas resolvem; o que não resolve é o meio-termo de hoje.
- `capturarStdout` deixa de ser bandeira — dois métodos que fazem coisas de verdade diferentes,
  ou um só, sem wrapper.

Este é o adapter que chama `ffmpeg` por processo externo e classifica a falha pelo exit code:
a classificação de falha permanente vs. transitória é o que o refactor não pode mexer. O
[ADR 0001](../../adr/0001-politica-de-falhas.md) é quem define essa política.

## Critérios de aceite

- [ ] Uma só forma de construir a falha permanente no arquivo
- [ ] Nenhum parâmetro-bandeira em `executar`
- [ ] A classificação por exit code segue idêntica; suíte do `extracao` verde com `ffmpeg` real
