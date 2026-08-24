# Roteiro do video de ate 10 minutos

- id: 024
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por: 023

## Question

O entregavel final e um video de **no maximo 10 minutos** apresentando documentacao,
arquitetura escolhida e o projeto funcionando. Dez minutos e pouco para tres coisas, entao o
roteiro e sobretudo um **orcamento de tempo** e uma ordem — nao um passeio pelo repositorio.

Duas pecas ja existem e mudam a conta: `scripts/smoke.sh` (ticket 022) executa o "projeto
funcionando" inteiro em ~1 minuto, narrado, sem ninguem digitar comando ao vivo; e o artefato
do ticket 023 e o que sustenta a parte de arquitetura. O que falta decidir: quanto tempo cada
bloco recebe, em que ordem (arquitetura antes ou depois de ver funcionar?), o que **nao**
mostrar — codigo linha a linha, Swagger clicado a mao, consoles de RabbitMQ e MinIO sao os
candidatos obvios a cortar —, se a execucao e ao vivo ou gravada de antemao (o risco de um
`docker compose pull` lento no meio da apresentacao e real), e quais requisitos do enunciado
precisam ser **ditos** porque nao aparecem na tela: nao perder requisicao em pico,
escalabilidade horizontal, qualidade por testes.

## Resolucao

[`docs/roteiro-video.md`](../../roteiro-video.md): narracao integral, palavra a palavra, com
a lista de tomadas. Fecha em **9:19** de audio (1.352 palavras a 145 wpm), com 41 segundos de
margem sob o teto de dez minutos.

**O fluxo de producao mudou o formato do entregavel.** A gravacao e em tres passos — filmar,
editar, dublar por cima —, e isso desacopla a narracao do tempo de execucao: o video vira
material acelaravel e o audio e o unico com teto rigido. Logo a unidade de orcamento e a
**palavra**, nao o segundo, e o roteiro tinha de ser narracao integral e nao topicos. Topico
funciona para quem locuta ao vivo; quem dubla, le. O ganho apareceu na hora: a primeira versao
media **9:42**, e o corte saiu no editor de texto em vez de sair numa regravacao.

**Onde o estouro estava.** Medindo tomada a tomada, ele nao estava distribuido — estava quase
todo numa peca: o diagrama do caminho de falha pedia 201 palavras num slot de 50s. E e
justamente a tomada que carrega as duas garantias que nenhuma demonstracao mostra (fila
quorum com ack manual e limite de entregas; unicidade do e-mail no `UPDATE` condicional).
Cortar por igual teria gutado o trecho mais caro do video, entao ela foi enxugada para 176 e
**ganhou tempo** (73s), tirado das tomadas que so descrevem o que ja esta na tela. Os rotulos
de segundo de cada tomada foram reescritos para a medicao real: um roteiro cujos tempos mentem
nao serve para editar em cima.

**Ordem: funcionando antes de arquitetura**, invertendo a do enunciado. Quem ve o `202` e o
polling antes dos diagramas entende por que os diagramas sao assim; quem ve quatro minutos de
caixas antes de saber o que o sistema faz, desliga. E **"Documentacao" nao virou bloco**: a
documentacao *e* o que esta na tela durante a arquitetura — os cinco Mermaid do
`arquitetura.md` sao o artefato. Bloco proprio de documentacao seria o passeio pelo
repositorio que este ticket existia para impedir.

**Edicao: acelerar com marca `4x`, nunca cortar**, dentro da demo. A distincao nao e estetica.
Acelerar preserva a continuidade, e o avaliador ve que nada foi retirado do meio da prova; um
corte dentro de uma verificacao levanta exatamente a duvida que a demonstracao existe para
fechar. Acelerar sem avisar equivale a cortar, dai a marca na tela. O `docker compose up`
ficou **fora** do video — unico trecho longo que nao demonstra nada — e virou frase.

**Duas limitacoes sao ditas em voz alta** no fechamento: a escalabilidade e argumentada e nao
medida, e o e-mail e *pelo menos uma vez*. Ambas ja estavam escritas em `docs/arquitetura.md`;
dize-las custa 20 segundos e uma banca que encontra sozinha uma limitacao nao declarada
desconta muito mais que isso.

**Sem slides.** Os diagramas vao renderizados em tela cheia com `mermaid-cli`, nao rolando um
Markdown num editor — assim tudo que aparece na tela e artefato versionado, o que e em si um
argumento sobre a documentacao. Cortados: codigo linha a linha, Swagger clicado a mao, console
do RabbitMQ e do MinIO. Mantida **uma** evidencia visual de testes (25s no run verde do
Actions), porque "cento e trinta testes" dito sem imagem e assercao — e a mesma tela cobre o
requisito de CI/CD.

**Achados de conferencia.** Todas as afirmacoes numericas da narracao foram checadas contra o
codigo, nao contra a memoria: `409` no pacote de Video que falhou, `ARQUIVO_INVALIDO`,
`@Scheduled(every = "30s")`, `max-outstanding-messages=1` no `extracao`. E uma armadilha de
filmagem: o `init-project.sh` deixou seis diretorios `com/example/` vazios nos tres modulos —
o git nao rastreia diretorio vazio, entao ninguem que clona o repositorio os ve, mas um `tree`
da copia local os poria na tela. A tomada da arvore de modulos filma `git ls-files`, e o
checklist de preparo registra o porque.

Ponteiro acrescentado ao "Mapa do repositorio" do `README.md`.
