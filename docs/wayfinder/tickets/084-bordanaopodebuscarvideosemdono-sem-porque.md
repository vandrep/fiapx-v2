# `bordaNaoPodeBuscarVideoSemDono` não tem `porquê` em lugar nenhum

- id: 084
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Spec da revisão do trabalho do [ticket 078](078-regras-do-teste-arquitetural-sem-linha-no-agents.md),
convertido em ticket.

## O problema

O `ArchitectureConstraintsTest` tem a regra `bordaNaoPodeBuscarVideoSemDono`: `Resource` e
`Controller` não podem chamar `.buscarPorId(`, só `.buscarPorIdEDono(`. A mensagem de violação
diz a troca ("use buscarPorIdEDono") mas não diz por quê.

Ao contrário de `processoExternoSoDeveApareceEmFramework`, que tem javadoc citando os tickets
006 e 015 e o motivo, `bordaNaoPodeBuscarVideoSemDono` não tem comentário nem javadoc — nem no
teste, nem no `AGENTS.md`. O 078 considerou incluí-la na série narrada em
§ *As três cópias do teste arquitetural*, mas decidiu que documentar essa regra é trabalho
próprio, fora do escopo do que aquele ticket pediu (a sétima e a oitava entrada, especificamente).

## O que entregar

Uma entrada na série do § *As três cópias do teste arquitetural* — no mesmo formato das outras,
o que a regra cobra e por que existe — ou, se o porquê estiver noutro lugar do repositório
(commit, ADR, outro ticket), a linha bastando apontar para lá em vez de reconstruí-lo.

## Critérios de aceite

- [ ] `bordaNaoPodeBuscarVideoSemDono` tem uma linha no `AGENTS.md`, ou uma referência a onde o
  porquê já está registrado
- [ ] A entrada explica por que buscar `Video` sem checar dono é o defeito que a regra evita,
  não só o que ela cobra
