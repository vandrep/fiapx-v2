# O rastreador carrega duas políticas opostas sobre reescrever ticket fechado

- id: 082
- label: ready-for-human
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

Dois tickets do mesmo intervalo tomaram decisões opostas sobre a mesma questão, e as duas estão
no repositório agora.

O [074](074-remover-o-ferramental-de-agente-versionado.md), § *Documentação que passa a mentir*,
fixa a regra pela negativa e a aplica a si mesmo:

> `docs/wayfinder/tickets/055-*.md` § Resolução — é registro histórico de um ticket fechado e
> **não se reescreve**. A reversão se registra no mapa e no README, não apagando o passado.

O [072](072-rastreador-contradiz-a-propria-convencao.md), no commit anterior, reescreveu o corpo
de catorze tickets fechados que estavam fora da própria lista de alvos — 023, 024, 029, 031,
032, 033, 034, 036, 037, 038, 039, 040, 041, 042 e 056 —, e em três deles (029, 031, 032)
**escreveu uma `## Resolução` que não existia**, redigida, nas palavras do próprio ticket, "a
partir do que o código mostra hoje".

As duas coisas podem estar certas: normalizar um cabeçalho fora de convenção não é o mesmo que
reescrever a narrativa de uma decisão, e reconstruir uma resolução a partir do código atual é
diferente das duas. Mas o rastreador não distingue, então a próxima sessão escolhe pela última
que leu.

`docs/wayfinder/TRACKER.md` e `docs/agents/issue-tracker.md` descrevem como resolver e como
marcar fora de escopo. Nenhum dos dois diz o que pode ser tocado depois de `status: fechado`.

## Por que é `ready-for-human`

É decisão de mantenedor sobre a convenção do próprio registro, não trabalho especificado. Um
agente que a tomasse estaria escolhendo entre duas políticas que o mantenedor já escreveu, em
dois lugares, sem saber qual delas ele quis.

## As perguntas

1. O que pode mudar num ticket `fechado`? Distinguir, no mínimo: cabeçalho e metadados (`label`,
   `status`, `bloqueado-por`), links quebrados, corpo narrativo, e `## Resolução`.
2. Reconstruir a resolução ausente de um ticket antigo a partir do código atual é registro ou é
   invenção? Se é permitido, a reconstrução se marca como tal?
3. Quando a decisão de um ticket fechado é revertida, onde a reversão mora? O 074 respondeu "no
   mapa, não apagando o passado" — isso vira regra geral?

## O que entregar

A resposta escrita em `docs/wayfinder/TRACKER.md`, na seção de operações, e refletida em
`docs/agents/issue-tracker.md` se a convenção mudar de forma. Os tickets já reescritos pelo 072
**não** são revertidos por este ticket: ele fixa a regra daqui para frente.

## Critérios de aceite

- [ ] `TRACKER.md` diz o que pode e o que não pode mudar num ticket `fechado`
- [ ] A regra cobre `## Resolução` reconstruída a partir do código
- [ ] A regra cobre onde mora a reversão de uma decisão registrada
- [ ] `docs/agents/issue-tracker.md` não contradiz o `TRACKER.md`
