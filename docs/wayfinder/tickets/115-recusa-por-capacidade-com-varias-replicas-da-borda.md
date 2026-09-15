# Recusa por capacidade com várias réplicas da borda

- id: 115
- label: ready-for-agent
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 72dc146)
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

- [x] Rodadas com N=3, teto baixo e teto derivado: comando, configuração e resultado registrados,
      com as recusas por réplica.
- [x] Resposta escrita: o `503` de capacidade tira a réplica de circulação no proxy de carga?
- [x] Recomendação sobre `http_503` no `proxy_next_upstream`. Se mudar, `mata-replica 3` antes e
      depois, sem regressão no custo de matar réplica.
- [x] O limite da reserva por réplica levado à seção de limitações do `docs/arquitetura.md`,
      coordenado com o [ticket 109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md),
      que edita a mesma seção.
- [x] Linha em "Decisões até aqui" no mapa.

## Resolução

Medido em 2026-09-15 sobre `develop @ 72dc146`. Método, imagens, comandos, contagens e
limites em [`capacidade-borda-replicas.md`](../../pesquisa/capacidade-borda-replicas.md).

Com N=3 e teto 1: 86 `202`, 314 `503`, recusas por réplica 70/75/169. Todos os 86 aceitos
chegaram a `CONCLUIDO`, sem frames errados. Com teto derivado de 2354 em cada réplica:
400/400 `202`, zero recusas por réplica, 400 `CONCLUIDO` e os seis critérios verdes.

**O `503` de capacidade não desabilitou a réplica nesta configuração.** Nos dois logs do
proxy, as únicas desabilitações foram por conexão recusada no healthcheck durante o boot,
antes da rajada. O caminho de resposta HTTP do nginx 1.27.5 exige `non_idempotent` para
acionar a troca de upstream de um `POST` já enviado; sem ele, também não conta essa falha.

**Mantido `http_503`.** Não houve defeito observado que justificasse removê-lo. Como o proxy
não mudou, não se aplica a remedição antes/depois de `mata-replica 3`; este ticket não
renova a medição do custo de matar réplica do 028.

A reserva local sobre volume compartilhado entrou em *Limitações conhecidas* da arquitetura,
junto dos itens já entregues pelo 109, sem reescrevê-los. A linha no mapa registra a decisão.
Nenhuma mudança de código Java ou configuração; não houve novo teste unitário a escrever
com TDD. A fronteira exercitada foi o HTTP através do proxy, pelo harness pedido no ticket.

### Validação e revisão

`./mvnw test` na raiz: **BUILD SUCCESS**, 512 testes (193 no `videos`, 290 no `extracao`,
29 no `notificacao`), zero falhas, erros ou ignorados. A primeira execução parou porque
o Keycloak do Compose ocupava a porta 8081 dos testes; ele foi parado temporariamente,
a suíte completa foi repetida e o container foi reiniciado ao final.

`/code-review` contra `72dc146`, em dois agentes independentes: Standards, zero achados;
Spec, zero achados. A revisão conferiu também as contagens e os logs locais das rodadas.
