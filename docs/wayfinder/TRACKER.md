# Tracker local (markdown)

Este repositório não tem issue tracker configurado, então o wayfinder usa arquivos.

- **Mapa**: `docs/wayfinder/map.md` (label `wayfinder:map`)
- **Tickets**: `docs/wayfinder/tickets/NNN-<slug>.md`, um por arquivo

Cada ticket tem um cabeçalho com `id`, `label`, `status`, `assignee` e `bloqueado-por`.

## Operações de wayfinding

| Operação | Como fazer aqui |
|---|---|
| Criar ticket | Novo arquivo `tickets/NNN-<slug>.md` com o cabeçalho padrão |
| Filho do mapa | Todos os tickets em `tickets/` são filhos do mapa |
| Bloquear | Campo `bloqueado-por:` com os ids dos tickets bloqueantes |
| Reivindicar | Preencher `assignee:` **antes** de qualquer trabalho |
| Fronteira | `status: aberto` **e** `bloqueado-por:` vazio ou só com ids fechados **e** `assignee:` vazio |
| Resolver | Adicionar seção `## Resolução` ao ticket, `status: fechado`, e uma linha em "Decisões até aqui" no mapa |
| Fora de escopo | `status: fechado`, `label: wayfinder:fora-de-escopo`, e uma linha em "Fora de escopo" no mapa |
