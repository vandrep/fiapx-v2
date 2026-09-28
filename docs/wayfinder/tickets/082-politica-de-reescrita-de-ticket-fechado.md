# O rastreador carrega duas políticas opostas sobre reescrever ticket fechado

- id: 082
- label: ready-for-human
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-08)
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

- [x] `TRACKER.md` diz o que pode e o que não pode mudar num ticket `fechado`
- [x] A regra cobre `## Resolução` reconstruída a partir do código
- [x] A regra cobre onde mora a reversão de uma decisão registrada
- [x] `docs/agents/issue-tracker.md` não contradiz o `TRACKER.md`

## Resolução

O conflito era menos frontal do que o ticket supunha: o [072](072-rastreador-contradiz-a-propria-convencao.md)
fez três coisas num commit só, e só a terceira encosta no que o
[074](074-remover-o-ferramental-de-agente-versionado.md) proibiu. Normalizar `status: resolvido`
para `fechado` mexe em campo de consulta; renomear `## Solução` para `## Resolução` no 038 mexe
em cabeçalho, com o texto intacto; escrever `## Resolução` do zero em 029, 031 e 032 é texto
novo, redigido meses depois, ocupando o lugar de um registro contemporâneo. O `TRACKER.md` agora
separa os três casos em § *O que pode mudar num ticket `fechado`*.

A regra que ficou: **um ticket fechado é registro do que se decidiu na época, não documentação
do estado atual do código.** Metadados e links quebrados mudam; corpo narrativo e `## Resolução`
já escritos, não. Erro descoberto depois vira seção nova, porque o parágrafo errado é parte do
que aconteceu.

Reconstrução de resolução ausente ficou **permitida e marcada**, como
`## Resolução (reconstruída em AAAA-MM-DD)` com a primeira linha dizendo que não veio da sessão
que fechou o ticket. Proibir deixaria os dez fechados sem resolução invisíveis para as duas
consultas do rastreador — que foi o defeito que o 072 saiu para consertar; permitir sem marcar
transforma inferência em memória. Renomear cabeçalho sobre conteúdo existente não é
reconstrução e não leva marca.

A resposta do 074 sobre reversão virou regra geral: ela mora no mapa e num ticket novo, e o
ticket revertido ganha no máximo um ponteiro de uma linha.

Uma decisão além do que o ticket pedia, tomada com aprovação do usuário: os três tickets já
reconstruídos pelo 072 **ganharam a marca retroativa**. O ticket dizia que eles não seriam
revertidos, e não foram — acrescentar o rótulo é adição no lugar onde a seção já está, que a
própria regra 1 permite. Sem isso, o 029 seguiria afirmando um veredito de carga ("241 s, limite
de 240 s") com a autoridade de quem estava lá.

`docs/agents/issue-tracker.md` ganhou a mesma regra em resumo, apontando para o `TRACKER.md`
como fonte.
