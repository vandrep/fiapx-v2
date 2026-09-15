# A tomada dos passos 6 a 8 do roteiro diz "três entregas depois" para uma falha permanente

- id: 117
- label: ready-for-agent
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 7661389)
- bloqueado-por:
- prioridade: P2

## Origem

Deixado de fora pelo [ticket 111](111-diagramas-e-narracao-coerentes-com-o-codigo.md), em
2026-09-14, sobre `develop @ 5e4e403`. A tomada não estava na lista do 111, e o mantenedor aprovou
abrir este ticket.

## O problema

A tomada "passos 6 a 8, caminho de falha" do Bloco 2 de `docs/roteiro-video.md` diz:

> quem prova que aquilo não é vídeo é o ffmpeg, três entregas depois. O vídeo termina em FALHOU
> com o motivo `ARQUIVO_INVALIDO`

Pelo código, isso está errado. Conferido em 2026-09-14:

- o arquivo que o `scripts/smoke.sh` envia (`extracao/src/test/resources/fixtures/arquivo-invalido.txt`)
  é recusado pelo **`ffprobe`**, com exit 1, antes de o `ffmpeg` rodar
  (`FfmpegExtracaoDeFramesAdapter.medirDuracaoEValidarStreamDeVideo`);
- essa recusa é `FalhaPermanenteDeExtracaoException` com `ARQUIVO_INVALIDO`. O
  `ProcessarExtracaoUseCase` publica `ExtracaoFalhou` e o consumidor dá ack **na primeira
  entrega**, sem gastar o `x-delivery-limit`;
- três entregas e DLQ são o caminho da falha **transitória**, e o motivo que sai dele é
  `TENTATIVAS_ESGOTADAS` (`ProcessarTentativasEsgotadasUseCase`).

Desde o 111, o diagrama do caminho de falha em `docs/arquitetura.md` mostra isso. Por isso a
narração, que sai por cima do smoke, agora contradiz o diagrama que aparece no Bloco 3 do mesmo
vídeo. Um avaliador que conte as entregas na tela não vai achar três.

## O que fazer

Reescrever a frase para dizer o que o smoke mostra: a falha é permanente, é detectada na primeira
entrega e não gasta tentativas. Mantenha "a borda aceita, a prova é do `extracao`", que é o ponto
da tomada. Trocar "ffmpeg" por "ffprobe" é opcional: vale se couber no orçamento de palavras.

Se a narração quiser contrastar os dois caminhos (permanente sai na hora, transitória gasta três
entregas), a tomada do caminho de falha do Bloco 3 já fala das três entregas. Não repita isso aqui.

## A restrição do roteiro

É a mesma do [ticket 111](111-diagramas-e-narracao-coerentes-com-o-codigo.md) § *A restrição do
roteiro*. O orçamento é de palavras, e o teto está no cabeçalho do bloco: o Bloco 2 tem hoje 393.
Conte como o 111 contou, somando as palavras das linhas `>` do bloco; essa soma reproduz o
cabeçalho. Se não couber, pare e peça decisão ao mantenedor, em vez de cortar outra tomada.

## Fora do escopo

- A tomada "sequência do caminho de falha" do Bloco 3, que ainda diz "o estado predecessor", no
  singular. Foi registrada no 111 e não entra aqui, a não ser que o mantenedor peça.
- O `scripts/smoke.sh` e os diagramas, que já estão certos.

## Critérios de aceite

- [x] A tomada dos passos 6 a 8 não diz que a falha do arquivo inválido leva três entregas.
- [x] O que a tomada afirma sobre a falha bate com o código e com o diagrama do caminho de falha.
- [x] A contagem do Bloco 2 continua dentro do teto, e os números do cabeçalho são atualizados.
- [x] Linha em "Decisões até aqui" no mapa.

## Resolução

Implementado em 2026-09-15 sobre `develop @ 7661389`.

A tomada dos passos 6 a 8 agora preserva a distinção da borda: ela aceita o arquivo pela extensão
e pelo content-type, mas a prova é do `extracao`, que recusa o conteúdo com `ffprobe` na primeira
entrega. A falha termina em `FALHOU` com `ARQUIVO_INVALIDO` e não gasta tentativas. O trecho do
Bloco 3, que descreve as três entregas do caminho transitório e `TENTATIVAS_ESGOTADAS`, ficou
intocado.

A contagem foi refeita somando as palavras das linhas `>`: o Bloco 2 ficou com **392 palavras**;
o total do roteiro passou a **1.403**, mantendo a duração arredondada de 9:41. A decisão foi
registrada no mapa.
