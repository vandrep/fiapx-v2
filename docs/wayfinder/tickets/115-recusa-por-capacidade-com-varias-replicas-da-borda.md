# Recusa por capacidade com várias réplicas da borda

- id: 115
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por: 114
- prioridade: P3

## Origem

Revisão do [ticket 108](108-recusa-por-capacidade-antes-do-corpo.md), em 2026-09-14, sobre
`develop @ 0ec5e9b`. O 108 calibrou a recusa só com uma réplica do `videos`. O mantenedor aprovou
este corte em 2026-09-14.

## O problema

Duas coisas sobre a recusa com N réplicas atrás do proxy de carga foram lidas no código e nunca
medidas:

1. **O proxy pode tratar a recusa como falha da réplica.** O `nginx.conf` do `videos-proxy` tem
   `proxy_next_upstream ... http_503` e `max_fails=1 fail_timeout=2s`. Um `503` de capacidade
   pode tirar a réplica de circulação por 2 s. Isso concentraria a rajada nas outras e produziria
   recusa em cascata. O `POST` não é reencaminhado, porque falta `non_idempotent`, mas a
   contagem de falha pode acontecer do mesmo jeito. Não se sabe qual dos dois efeitos domina.
2. **A reserva de espaço é por réplica.** As réplicas montam o mesmo volume `fiapx-uploads`, e cada
   uma deriva o teto do volume inteiro e desconta só as próprias reservas. A proteção contra
   "dois envios enchem o volume juntos" só vale inteira com uma réplica. Está escrito no contrato
   HTTP, em § *Recusa por capacidade*.

## O que fazer

Medir com `scripts/carga/borda.sh escala 3` e `FIAPX_BORDA_TETO_DE_ENVIOS_SIMULTANEOS` baixo, de
modo que a recusa aconteça. Uma rodada de controle com o teto derivado. Comparar recusas por
réplica, `202` totais e o log do proxy, que diz se ele marcou réplica como indisponível.

A partir do resultado, recomendar se o proxy de carga deve contar o `503` como falha da réplica.
Se a recomendação for mudar, a mudança entra neste ticket, remedida. O proxy é instrumento, e a
mudança não pode alterar o que o [ticket 028](028-escala-da-borda.md) mediu sobre matar réplica.
Rodar `borda.sh mata-replica 3` antes e depois.

## Critérios de aceite

- [ ] Rodadas com N=3, teto baixo e teto derivado: comando, configuração e resultado registrados,
      com as recusas por réplica.
- [ ] Resposta escrita: o `503` de capacidade tira a réplica de circulação no proxy de carga?
- [ ] Recomendação sobre `http_503` no `proxy_next_upstream`. Se mudar, `mata-replica 3` antes e
      depois, sem regressão no custo de matar réplica.
- [ ] O limite da reserva por réplica levado à seção de limitações do `docs/arquitetura.md`,
      coordenado com o [ticket 109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md),
      que edita a mesma seção.
- [ ] Linha em "Decisões até aqui" no mapa.
