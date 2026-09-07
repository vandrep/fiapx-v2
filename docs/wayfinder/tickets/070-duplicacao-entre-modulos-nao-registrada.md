# A duplicação entre módulos não está registrada como decisão

- id: 070
- label: ready-for-human
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7`, convertido em ticket com
aprovação do usuário. É pergunta antes de ser tarefa: a decisão é do mantenedor.

## O problema

Quatro corpos de código existem em cópias entre os três serviços:

| Cópia | Onde | Tamanho |
|---|---|---|
| `Rastro` | três serviços | ~230 linhas, difere só em javadoc e no `marcar` do `videos` |
| `JsonObjectPayloadConverter` | três serviços | idêntico |
| `comRepeticao` | três serviços | mesmas constantes |
| `MotivoFalha.doCodigo` | `videos`, `notificacao` | — |

Isso **não é defeito**: o mapa descarta o módulo `shared` duas vezes, nas Notas e em Fora de
escopo, e `docs/arquitetura.md` dá o motivo — duplicar cinco records é mais honesto que acoplar
três serviços por um jar. O repo prevalece sobre o smell, e o ticket não propõe reverter isso.

O que falta é o registro. Compare com as três cópias do `ArchitectureConstraintsTest`: elas têm
parágrafo próprio no AGENTS.md ("Editou uma, edite as três"), explicam por que não há módulo
`test-support`, e têm `scripts/verifica-testes-arquiteturais.sh` reprovando o build na primeira
divergência. As cópias do `Rastro` não têm nada disso — e o `Rastro` é o maior corpo duplicado
do repositório, recém-corrigido pelo [ticket 063](063-escopo-do-rastro-atravessa-thread.md)
numa correção que precisou ser aplicada nas três, exatamente o cenário que a guarda do teste
arquitetural existe para pegar.

A justificativa escrita cobre o **contrato de evento** — cinco records. Ela não foi escrita
pensando em ~230 linhas de lógica de instrumentação com um par abre/fecha sensível a thread.

## As perguntas

1. A decisão de duplicar continua valendo para o `Rastro`, agora que ele é o que é? Se sim, o
   AGENTS.md ganha o parágrafo que falta, no mesmo lugar e no mesmo tom do das três cópias do
   teste.
2. O `Rastro` merece guarda de divergência, no molde do `verifica-testes-arquiteturais.sh`? Ele
   não é byte a byte idêntico hoje (o `marcar` do `videos` difere), então a guarda precisaria
   comparar o que é comum — o que talvez seja motivo suficiente para não ter guarda nenhuma e
   dizer isso por escrito.
3. As outras três cópias entram no mesmo parágrafo ou não merecem menção?

## O que entregar

A decisão registrada onde ela vai ser lida por quem for tocar nas cópias — AGENTS.md, com o
mapa registrando a linha em "Decisões até aqui". Se a resposta a (2) for sim, a guarda também.
Uma decisão explícita de **não** guardar fecha o ticket igualmente.

## Critérios de aceite

- [ ] AGENTS.md diz, sem o leitor precisar deduzir, que as cópias são deliberadas e por quê
- [ ] A pergunta da guarda do `Rastro` tem resposta escrita, seja qual for
- [ ] Uma linha em "Decisões até aqui" no mapa
