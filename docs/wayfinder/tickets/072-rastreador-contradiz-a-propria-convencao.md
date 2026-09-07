# O rastreador contradiz a própria convenção

- id: 072
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...39712e7`, convertido em ticket com aprovação do
usuário.

## O problema

`docs/wayfinder/TRACKER.md` e `docs/agents/issue-tracker.md` definem duas operações de consulta,
e as duas se apoiam no campo `status`: a **fronteira** é `status: aberto` sem bloqueio e sem
`assignee`; **resolver** é `status: fechado` com seção `## Resolução` e uma linha em "Decisões
até aqui" no mapa. Três desvios quebram isso hoje:

1. **Seis tickets em `status: resolvido`** — 038, 039, 040, 041, 042 e 056. O valor não existe na
   convenção. A consequência é concreta e é pior do que inconsistência cosmética: eles não casam
   nem a consulta de fronteira nem a de resolvido, então somem das duas. Um agente que pergunte
   "o que falta?" não os vê, e um humano que pergunte "o que já foi feito?" também não.
2. **O 038 não tem `## Resolução`** — usa `## Solução` e `## Validação`. A skill que busca a
   decisão de um ticket procura pelo cabeçalho canônico.
3. **Seis tickets sem linha no mapa** em "Decisões até aqui": 040, 042, 043, 045, 056 e 057.
   Destes, 043, 045 e 057 já estão `fechado`, então falham o critério de resolução explícito da
   convenção. Os vizinhos imediatos (044, 046, 047, 059-063) têm a linha — a ausência é
   irregular, não um padrão diferente.

## O que entregar

Uma varredura só, deixando o rastreador consistente com o que ele mesmo declara:

- Os seis `resolvido` viram `fechado`. Confirme antes, ticket a ticket, que o trabalho **está**
  concluído no código: se algum estiver de fato pendente, ele vira `aberto`, e o ticket registra
  qual e por quê. Não presuma que `resolvido` significava `fechado` em todos os seis.
- O 038 ganha a seção `## Resolução`, aproveitando o conteúdo que já está lá sob os outros dois
  cabeçalhos.
- Os seis ausentes ganham a linha em "Decisões até aqui", no formato que o mapa já usa
  — um item de lista com o título linkando o arquivo do ticket, travessão, e o que se
  decidiu. A linha diz a **decisão**, não o
  título repetido; leia a `## Resolução` de cada um para escrevê-la.

## Critérios de aceite

- [ ] Nenhum ticket com `status` fora de `aberto`/`fechado`
- [ ] Todo ticket `fechado` tem seção `## Resolução` e uma linha em "Decisões até aqui"
- [ ] A consulta de fronteira do wayfinder devolve um resultado coerente com o estado real do trabalho
