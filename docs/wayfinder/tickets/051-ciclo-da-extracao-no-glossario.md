# Ciclo da Extração ganha nome no glossário e superfície honesta

- id: 051
- label: wayfinder:task
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...b4672ff`, convertido em ticket com
aprovação do usuário. Duas observações sobre a mesma peça. A primeira é regra documentada:
o AGENTS.md manda que todo termo de domínio usado em código passe pelo glossário canônico, e
"Ciclo da Extração" não está lá — o glossário tem Extração, tentativa, Pacote e
Estacionamento. A segunda é heurística de manutenção: parte dos métodos dessa peça só
traduz um booleano em um opcional, sem carregar regra própria, enquanto a classificação da
falha do ffmpeg e a validação da contagem de frames carregam. Não há defeito funcional.

## O que entregar

Quem lê o código da classificação de falha do `extracao` encontra cada termo no glossário e
uma superfície onde cada método existe por carregar regra. O comportamento de classificação
observado de fora — motivo da falha, permanente ou transitória — não muda.

## Condições de aceite

- [x] O termo usado pelo tipo existe no glossário canônico, ou o tipo passa a usar
  vocabulário que já existe lá; a escolha registrada com a razão.
- [x] Os métodos que só traduzem um booleano em um opcional saem do caminho, sem perder
  legibilidade no ponto de chamada.
- [x] Os motivos de falha e a distinção entre permanente e transitória saem idênticos para
  cada entrada que a suíte hoje exercita.
- [x] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

**O tipo passou a usar o vocabulário que já existe, em vez de o glossário ganhar um verbete
novo.** `CicloDaExtracao` virou `Extracao` — o termo que o `CONTEXT.md` já define como "a
operação que lê um Vídeo e produz seus frames". A razão de não inventar o verbete: "Ciclo"
não nomeava nada que a Extração já não nomeasse. A classe não modela um ciclo de vida com
estados próprios; ela carrega as regras que decidem o desfecho de *uma* Extração a partir dos
sinais do `ffmpeg` e do `ffprobe`. Um verbete "Ciclo da Extração" no glossário obrigaria o
leitor a distinguir dois termos onde o domínio tem um só — e o glossário é o lugar onde essa
duplicação custa mais caro, porque ele é a autoridade que o `AGENTS.md` manda consultar. O
`CONTEXT.md` fica intocado de propósito: ele já dizia o necessário, e o defeito era o código
falando fora dele.

O nome não colide: `Extracao` é a primeira entidade do módulo com esse nome, e os vizinhos
(`ResultadoExtracao`, `ExtracaoController`, `ExtracaoDeFramesGateway`) qualificam o que são.

**Dois métodos saíram.** `motivoSeSondagemFalhou(int)` e `motivoAoValidarFluxoDeVideo(boolean)`
eram `ternário → Optional`: nenhum limiar, nenhuma tolerância, nenhuma comparação — só a
escolha de um `MotivoFalha` para um booleano que o chamador já tinha em mãos. Ficaram no
adapter como duas guardas com o motivo explícito:

```java
if (streamDeVideo.exitCode() != 0) {
    lancarFalhaPermanente(MotivoFalha.ARQUIVO_INVALIDO, detalheDoStream);
}
if (streamDeVideo.stdout() == null || streamDeVideo.stdout().isBlank()) {
    lancarFalhaPermanente(MotivoFalha.SEM_FLUXO_DE_VIDEO, detalheDoStream);
}
```

O ponto de chamada ficou mais direto, não menos: a cadeia `.or(...).ifPresent(...)` escondia a
ordem de precedência entre os dois motivos atrás da semântica do `Optional`, e agora ela é a
ordem das linhas. O `detalheTecnico` das duas falhas é o mesmo de antes, extraído para
`detalheDoStream` porque as duas guardas o compartilham.

**Os dois que carregam regra ficaram**: `motivoAoValidarDuracao` compara com o teto, e
`motivoAoValidarContagemDeFrames` conhece a tolerância de 10% sobre um frame por segundo. Junto
com `classificarFalhaDoFfmpeg`, que é a tabela de exit codes do ticket 006, é isso que sobrou
na entidade — cada método por carregar decisão que o adapter não tem como tomar.

Nenhuma classificação mudou. `ExtracaoTest` (ex-`CicloDaExtracaoTest`) mantém as 16 entradas de
`classificarFalhaDoFfmpeg` e as 4 de contagem de frames palavra por palavra; o teste de
sondagem, que exercitava as duas assinaturas removidas encadeadas com a duração, virou
`decideSeADuracaoExcedeOTeto` sobre a única das três que continua no domínio, com as mesmas
durações de antes mais o `Duration.ZERO` e os 30 segundos que as outras entradas carregavam.

Ele cobria dois caminhos que agora vivem no adapter, e os dois seguem cobrados, cada um onde
faz sentido:

- **`ffprobe` que sai diferente de zero vira `ARQUIVO_INVALIDO`** — é o que o cenário BDD *Um
  arquivo que não é vídeo vira falha permanente, sem Pacote* já exercita pela borda, com
  `arquivo-invalido.txt` e o binário de verdade, cobrando `ARQUIVO_INVALIDO` no evento
  `extracao.falhou`.
- **`ffprobe` que lê o arquivo sem erro mas não acha fluxo de vídeo vira
  `SEM_FLUXO_DE_VIDEO`** — este ficaria descoberto, e a revisão o pegou. O cenário BDD acima
  não o alcança: com um `.txt`, a *primeira* sondagem, a de duração, já sai diferente de zero
  e o fluxo termina antes da sondagem de stream. O `SEM_FLUXO_DE_VIDEO` que sobrou em
  `ExtracaoTest` é o da tabela de exit codes do ffmpeg (exit 234), que é outra decisão.
  Ganhou teste próprio: `SondagemSemFluxoDeVideoTest` roda o adapter contra
  `fixtures/somente-audio.wav` — 1 s de silêncio PCM, que o `ffprobe` lê sem erro e para o
  qual `-select_streams v:0` devolve vazio. Conferido por controle negativo (`expected:
  <ARQUIVO_INVALIDO> but was: <SEM_FLUXO_DE_VIDEO>`), então ele julga o motivo, não só a
  falha.

Fica registrado o que **não** ganhou teste: a guarda `streamDeVideo.exitCode() != 0`. Para o
`ffprobe` sair diferente de zero na segunda sondagem depois de ter saído zero na primeira, o
arquivo teria que mudar debaixo do processo entre as duas chamadas. Ela é defesa em
profundidade, herdada do que havia antes deste ticket, e permanece por isso.

A suíte inteira rodou a partir da raiz, com Docker e `ffmpeg` no `PATH` como o `AGENTS.md`
exige: `BUILD SUCCESS`, 425 testes nos três módulos, zero falhas. A porta de teste do `videos`
precisou sair do 8081 (`-Dquarkus.http.test-port=`) porque a stack de demo do Compose estava de
pé na máquina e o Keycloak dela ocupa essa porta — ambiente, não código.

## Achados da revisão que não viraram código

A revisão de dois eixos sobre este trabalho levantou o buraco do `SEM_FLUXO_DE_VIDEO` (acima,
fechado) e mais três coisas, registradas aqui em vez de mudadas:

- **`Extracao` é uma classe estática ocupando o nome mais valioso do módulo.** Verdade, e é o
  que o ticket pediu: o vocabulário do glossário no tipo que carrega as regras da Extração. A
  alternativa sugerida — mover `classificarFalhaDoFfmpeg` para dentro de `SinaisDoFfmpeg` e
  esvaziar a classe — é redesenho do classificador, que este ticket não pediu e cuja entrada
  (`SinaisDoFfmpeg`) é vocabulário de infraestrutura, não de domínio. Se o **052** precisar do
  nome `Extracao` para o resultado da Extração, a disputa se resolve lá, com as duas peças à
  vista.
- **O adapter agora sinaliza falha permanente de dois jeitos**: `if (...) throw` nas guardas
  sobre dados que ele mesmo tem, e `Optional...ifPresent` onde quem decidiu foi o domínio. A
  diferença é essa, e é informação: o `Optional` marca onde a regra veio de fora. O que a
  revisão apontou com razão foi o `lancarFalhaPermanente` — um `void` que sempre lançava,
  deixando o `throw` invisível no ponto de chamada. Virou `falhaPermanente`, que **devolve** a
  exceção com `throw` explícito em cada chamador, igual ao vizinho `criarFalhaDoFfmpeg`.
- **`detalheDoStream` diz "ffprobe de stream saiu com 0" quando o motivo é
  `SEM_FLUXO_DE_VIDEO`.** É enganoso, é anterior a este ticket (a mensagem já era compartilhada
  pelos dois motivos dentro do `ifPresent`) e é só diagnóstico — nunca chega ao usuário, que vê
  o código do motivo. Mexer nele mudaria saída que a condição de aceite manda deixar idêntica.
