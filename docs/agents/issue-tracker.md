# Rastreador de issues

Os mapas, as especificações e os tickets deste repositório vivem em Markdown
versionado, sob `docs/wayfinder/`. A convenção canônica é
[`docs/wayfinder/TRACKER.md`](../wayfinder/TRACKER.md).

Não use `.scratch/`.

## Convenções

- **Mapa**: `docs/wayfinder/map.md`, identificado pelo comentário
  `<!-- label: wayfinder:map -->`.
- **Especificação**: `docs/wayfinder/specs/<slug>.md`. Crie o diretório somente ao
  publicar a primeira especificação.
- **Ticket**: `docs/wayfinder/tickets/NNN-<slug>.md`, com número de três dígitos.
- **Relação com o mapa**: todo arquivo em `docs/wayfinder/tickets/` é filho do
  mapa.
- **Rótulo**: o campo `label` no cabeçalho do ticket.
- **Bloqueio**: o campo `bloqueado-por`, com os IDs dos tickets bloqueantes.
- **Reivindicação**: preencher `assignee` antes de começar o trabalho.
- **Resolução**: acrescentar `## Resolução`, mudar `status` para `fechado` e
  registrar a decisão no mapa quando houver uma.

## Quando uma skill menciona o rastreador

- **Publicar uma especificação**: criar
  `docs/wayfinder/specs/<slug>.md`, com o slug da funcionalidade.
- **Publicar um ticket**: criar o arquivo correspondente em
  `docs/wayfinder/tickets/`, seguindo o formato já existente.
- **Buscar um ticket**: abrir o arquivo referido em `docs/wayfinder/tickets/`.
- **Encontrar a fronteira do wayfinder**: considerar tickets com `status: aberto`,
  sem bloqueios abertos e sem `assignee`; em caso de empate, o menor ID vem primeiro.
- **Marcar fora de escopo**: usar `status: fechado`,
  `label: wayfinder:fora-de-escopo` e registrar a decisão no mapa.
