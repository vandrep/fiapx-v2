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

## O que pode mudar num ticket `fechado`

Um ticket fechado é registro do que se decidiu **na época**. Ele não é documentação do estado
atual do código — quem quer o estado atual lê o `CONTEXT.md`, os ADRs e o mapa.

| Parte do ticket | Depois de `fechado` |
|---|---|
| Metadados (`label`, `status`, `assignee`, `bloqueado-por`, `prioridade`) | Podem mudar |
| Links quebrados por arquivo movido ou renomeado | Podem ser corrigidos |
| Corpo narrativo e `## Resolução` já escritos | **Não se reescrevem nem se apagam** |
| Seções novas no fim do arquivo | Podem ser acrescentadas |

Erro de fato descoberto depois se corrige por **seção nova** (`## Correção (NNN)`), não editando
o parágrafo errado: o parágrafo errado é parte do que aconteceu.

### Resolução reconstruída

Um ticket fechado sem `## Resolução` pode ganhar uma, escrita a partir do código, para não
sumir das consultas. Ela se marca como tal, no cabeçalho e na primeira linha:

```markdown
## Resolução (reconstruída em AAAA-MM-DD)

*Escrita a partir do código, não pela sessão que fechou o ticket.*
```

Reconstrução é inferência com data; resolução contemporânea é memória. A marca é o que impede
que a segunda seja lida como a primeira. Aproveitar conteúdo que já está no ticket sob outro
cabeçalho não é reconstrução — é renomear cabeçalho, e não leva marca.

### Onde mora a reversão de uma decisão

No mapa e num ticket novo. O ticket revertido continua dizendo o que se decidiu na época — é
isso que o torna útil —, e ganha no máximo um ponteiro de uma linha no fim, para o ticket que
o reverteu.
