# O `Scope` do `Rastro` abre numa thread e fecha noutra

- id: 063
- label: ready-for-agent
- status: aberto
- assignee:
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
