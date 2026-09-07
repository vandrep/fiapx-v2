# Remover o ferramental de agente do repositório de entrega

- id: 074
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Decisão do mantenedor, tomada ao trabalhar o [ticket 071](071-agents-versionado-sem-justificativa.md):
não há valor em manter as skills versionadas aqui. Este ticket **substitui o 071** — aquele
perguntava se o ferramental fica ou sai, este executa a saída. O 071 fecha apontando para cá.

A decisão reverte o que o [ticket 055](055-registrar-as-escolhas-fora-do-enunciado.md) registrou.
Isso é deliberado, e o mapa precisa dizer que reverteu, não apagar a linha antiga.

## O problema

O repositório versiona três coisas que existem só para o agente operar:

| Caminho | O que é | Arquivos versionados |
|---|---|---|
| `.agents/skills/` | as 37 skills, arquivos reais | 100 |
| `.claude/skills/` | 37 symlinks (modo `120000`) para `../../.agents/skills/<nome>` | 37 |
| `skills-lock.json` | manifesto do instalador: `source: mattpocock/skills`, `skillPath`, `computedHash` por skill | 1 |

Os três entraram no mesmo commit, `5db854e chore: versiona as skills de agente do repositorio`
(04/09/2026). `.claude/skills/` e `.agents/skills/` **não são dois conjuntos**: um é a fachada
de symlinks do outro. Qualquer remoção tem de tratar os três juntos, ou deixa symlink apontando
para o vazio.

Nada disso é pedido pelo enunciado, nada disso é lido por `./mvnw verify`, e o avaliador que
abrir o repositório encontra 138 arquivos de processo antes de encontrar o código dos três
serviços.

## Impacto medido

**No fluxo de trabalho local: quase nenhum.** Existe instalação global em `~/.claude/skills`
com 25 skills, incluindo todo o caminho principal — `grill-with-docs`, `to-spec`, `to-tickets`,
`implement`, `code-review`, `tdd`, `wayfinder`, `triage`, `diagnosing-bugs`, `domain-modeling`.
Remover do repositório não tira nenhuma delas de quem já as tem instaladas.

**Some do repositório sem estar no global (12):** `claude-handoff`, `git-guardrails-claude-code`,
`implement-spec`, `loop-me`, `migrate-to-shoehorn`, `retro`, `scaffold-exercises`,
`setup-pre-commit`, `setup-ts-deep-modules`, `writing-beats`, `writing-fragments`,
`writing-shape`. Nenhuma é etapa do fluxo principal; `claude-handoff` e `implement-spec` são
variantes de `handoff` e `implement`, que ficam no global. Cinco delas (`migrate-to-shoehorn`,
`setup-ts-deep-modules`, `scaffold-exercises`, `writing-*`) nunca tiveram uso possível num
projeto Java. Confirmar essa lista antes de remover é parte do trabalho.

**Documentação que passa a mentir (3 pontos):**

- `README.md` § `Ferramental de agente versionado` (linha ~230) — defende `.claude/skills/` e
  `.devcontainer/`. O devcontainer **fica**; só a metade das skills sai.
- `docs/wayfinder/map.md` (linha ~614), na entrada de Decisões até aqui do ticket 055 — registra
  o parágrafo do README como resolvido.
- `docs/wayfinder/tickets/055-*.md` § Resolução — é registro histórico de um ticket fechado e
  **não se reescreve**. A reversão se registra no mapa e no README, não apagando o passado.

**O que não é afetado:** `CLAUDE.md` e `docs/agents/{issue-tracker,triage-labels,domain}.md`
descrevem convenções deste repositório — o rastreador em `docs/wayfinder/`, os cinco rótulos,
o layout de contexto único. Eles valem com a skill instalada de onde for, então ficam como
estão. `docs/wayfinder/` inteiro fica: é o registro do trabalho, não ferramenta.

## O que entregar

1. `.agents/`, `.claude/skills/` e `skills-lock.json` saem do rastreamento (`git rm --cached`,
   os arquivos continuam no disco de quem trabalha aqui) e entram no `.gitignore`, junto da
   entrada que já existe para `.claude/scheduled_tasks.lock`.
2. `README.md` § `Ferramental de agente versionado`: a seção deixa de citar as skills e passa a
   tratar só do `.devcontainer/`, cuja justificativa — fixar o toolchain para reproduzir
   `./mvnw verify` — continua inteira e independente. Ajustar o título, que hoje promete duas
   coisas.
3. `docs/wayfinder/map.md`: uma linha em **Fora de escopo** dizendo que o ferramental de agente
   deixou o repositório de entrega e por quê, referenciando este ticket e nomeando a reversão
   do 055. A linha existente do 055 em Decisões até aqui não se apaga.
4. `docs/wayfinder/tickets/071-*.md`: `status: fechado`, `## Resolução` apontando para cá.

## Como minimizar o impacto

- **`git rm --cached`, nunca `rm -rf`.** As skills seguem funcionando no diretório de trabalho
  depois da remoção; o que muda é só o rastreamento.
- **`.gitignore` antes do commit**, para que as 138 entradas não voltem como untracked no
  próximo `git add`.
- **Verificar os symlinks por último**: `git ls-files -s .claude` deve sair vazio, e nenhum
  caminho versionado pode apontar para dentro de `.agents/`.
- Rodar `grep -rn --exclude-dir=.git -e '\.agents/' -e '\.claude/skills' .` ao fim; as únicas
  ocorrências aceitáveis são as deste ticket, do 071 e do 055 (registro histórico).

## Critérios de aceite

- [x] `git ls-files .agents .claude skills-lock.json` não devolve nada
- [x] `.gitignore` cobre os três caminhos, com comentário dizendo por quê
- [x] Nenhum symlink versionado aponta para um caminho não rastreado
- [x] O README não promete mais uma seção sobre skills, e a justificativa do `.devcontainer/`
      continua legível sozinha
- [x] Uma linha em Fora de escopo no mapa, nomeando a reversão do 055
- [x] Ticket 071 fechado, com `## Resolução` apontando para o 074
- [x] `./mvnw verify` inalterado — nenhuma das mudanças toca o build

## Resolução

Escopo confirmado pelo mantenedor: **as 37 skills, não só as 8 inaplicáveis.**

`git rm -r --cached` em `.agents/`, `.claude/skills/` e `skills-lock.json` — 138 caminhos saíram
do índice e continuam no disco de quem trabalha aqui. Os três entraram no `.gitignore` num bloco
próprio, ao lado da entrada que já existia para `.claude/scheduled_tasks.lock`, com o comentário
dizendo por quê. `.claude/RESUME.md` nunca esteve versionado, então ignorar `.claude/skills/` em
vez de `.claude/` inteiro não deixou nada de fora.

`README.md`: a seção "Ferramental de agente versionado" virou "Por que o `.devcontainer/` está
versionado". O título antigo prometia duas coisas e agora só uma se sustenta. A justificativa
do devcontainer — fixar o toolchain para reproduzir `./mvnw verify` — ficou intacta, e um segundo
parágrafo registra o caminho oposto que o ferramental de agente tomou, para que a ausência seja
tão explicada quanto a presença era.

`docs/wayfinder/map.md`: linha nova em Fora de escopo, nomeando a reversão do 055. A entrada do
055 em Decisões até aqui não foi apagada — ganhou uma frase em itálico apontando para cá, porque
um mapa que registra a decisão antiga sem dizer que ela caiu é exatamente o defeito que o
[ticket 072](072-rastreador-contradiz-a-propria-convencao.md) descreve.

Verificações: `git ls-files .agents .claude skills-lock.json` devolve vazio; não resta nenhum
symlink versionado no repositório (`git ls-files -s | awk '$1=="120000"'` vazio); as únicas
menções aos caminhos são o `.gitignore`, o mapa e os tickets 055/071/074.

`./mvnw verify` não foi executado: o diff toca `.gitignore`, `README.md`, `docs/wayfinder/map.md`
e dois tickets, e nenhum arquivo de build, código ou teste mudou.
