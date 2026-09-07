# `.agents/` versionado sem justificativa

- id: 071
- label: ready-for-human
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...39712e7`, convertido em ticket com aprovação do
usuário. É pergunta antes de ser tarefa: a decisão é do mantenedor.

## O problema

O repositório rastreia **100 arquivos** em `.agents/`, mais o `skills-lock.json` na raiz.
Nenhum dos dois aparece no README, no AGENTS.md, no mapa ou em ticket algum. Entre eles há
skills sem nenhuma relação com um projeto Java de três serviços Quarkus —
`migrate-to-shoehorn` (TypeScript), `setup-ts-deep-modules`, `scaffold-exercises`,
`to-questionnaire`, `ask-matt`.

O incômodo não é o tamanho, é a lacuna: o [ticket 055](055-registrar-as-escolhas-fora-do-enunciado.md)
existiu para registrar as escolhas fora do enunciado, e registrou `.claude/skills/` e
`.devcontainer/` no README, § "Ferramental de agente versionado". Essa seção é hoje a resposta
oficial à pergunta "por que há ferramental de agente neste repo?", e ela não menciona `.agents/`.
Quem for avaliar o trabalho encontra 100 arquivos versionados que a documentação do próprio
repositório não explica.

## As perguntas

1. `.agents/` é intencional? Se for espelho ou artefato de instalação de outra ferramenta, ele
   pertence ao `.gitignore`, não ao histórico.
2. Se for intencional, as skills sem relação com o projeto ficam? Manter `migrate-to-shoehorn`
   num repositório sem uma linha de TypeScript é o tipo de coisa que o avaliador nota.
3. O `skills-lock.json` acompanha a decisão de (1) ou tem vida própria?

## O que entregar

Uma das duas, não as duas pela metade:

- **Sai**: `.agents/` e, se for o caso, `skills-lock.json` saem do rastreamento e entram no
  `.gitignore`, com uma linha em Fora de escopo no mapa dizendo por quê.
- **Fica**: o README § "Ferramental de agente versionado" passa a cobri-los com a mesma
  honestidade com que cobre `.claude/skills/` — incluindo o que veio junto e não serve para
  nada aqui —, e o mapa ganha a linha em "Decisões até aqui".

## Critérios de aceite

- [ ] `.agents/` e `skills-lock.json` ou saíram do rastreamento, ou estão explicados no README
- [ ] A decisão tem uma linha no mapa, em "Decisões até aqui" ou em "Fora de escopo"
- [ ] Nenhum arquivo versionado sob `.agents/` sem que a documentação diga por que ele está lá

## Resolução

Substituído pelo [ticket 074](074-remover-o-ferramental-de-agente-versionado.md), que executou
a saída.

As três perguntas foram respondidas antes disso:

1. **`.agents/` era intencional** — não era espelho nem artefato órfão. Os 37 caminhos
   versionados sob `.claude/skills/` eram symlinks (modo `120000`) para `../../.agents/skills/`,
   e os dois entraram no mesmo commit, `5db854e`. O ticket tratava como dois conjuntos o que
   era um só com duas fachadas, e por isso sua opção "Sai" estava mal formulada: mandar só
   `.agents/` para o `.gitignore` deixaria os symlinks apontando para o vazio.
2. **As skills sem relação com o projeto não ficam — e as demais também não.** O mantenedor
   decidiu remover as 37, não só as 8 inaplicáveis: o valor estava na instalação global, não no
   repositório de entrega.
3. **`skills-lock.json` acompanhou (1)**, como manifesto da mesma instalação.

O que este ticket pedia — que nada versionado sob `.agents/` ficasse sem explicação — foi
atendido pela via oposta à que ele previa: em vez de explicar os 138 arquivos, a entrega deixou
de rastreá-los.
