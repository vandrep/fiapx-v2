# O ticket 071 nasceu fechado, com os critérios de aceite em branco

- id: 083
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por: 082
- prioridade: P3

## Origem

Achado do eixo Spec da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

`docs/wayfinder/tickets/071-agents-versionado-sem-justificativa.md` não existe em `08d76ed`. Ele
foi criado já com `status: fechado`, no mesmo commit que resolveu o
[074](074-remover-o-ferramental-de-agente-versionado.md).

O 074 § *O que entregar*, item 4, pede:

> `docs/wayfinder/tickets/071-*.md`: `status: fechado`, `## Resolução` apontando para cá.

A redação pressupõe um arquivo que já existisse — o 074 fala de si mesmo como "substitui o 071",
e a `## Resolução` do 071 diz que "as três perguntas foram respondidas antes disso". O arquivo,
porém, foi escrito depois das respostas.

Duas consequências ficaram no registro:

- Os três critérios de aceite do 071 seguem `[ ]`. São os únicos assim entre os 74 tickets
  fechados, e pelo que a resolução narra, os três foram de fato atendidos — pela via oposta à
  que o ticket previa, mas atendidos.
- O `TRACKER.md` manda preencher `assignee` **antes** de qualquer trabalho, e o ciclo
  `aberto → reivindicado → fechado` não foi percorrido.

Não há defeito de código aqui, e a decisão do 074 não muda. O que está errado é o registro: um
ticket que nunca esteve aberto conta uma história que não aconteceu, e é o mesmo tipo de defeito
que o [072](072-rastreador-contradiz-a-propria-convencao.md) foi aberto para corrigir.

## Por que depende do 082

A correção óbvia — marcar os critérios e anotar como o ticket nasceu — é uma reescrita de ticket
fechado, exatamente o que o [082](082-politica-de-reescrita-de-ticket-fechado.md) vai decidir se
é permitido e sob que forma. Fazer antes seria escolher a política pelo caminho.

## O que entregar

Conforme a política que o 082 fixar:

- Os três critérios do 071 marcados, se a política permitir tocá-los, com a nota de que foram
  atendidos pela via do 074.
- Ou, se a política proibir, uma linha no mapa registrando que o 071 é um ticket retroativo e
  por quê — a informação fica, o registro histórico não é reescrito.

Em qualquer dos dois casos, o registro precisa deixar claro que o 071 documenta uma decisão já
tomada, não um trabalho conduzido pelo ciclo do rastreador.

## Critérios de aceite

- [ ] O 071 não fica mais como único ticket fechado com aceite em branco e sem explicação
- [ ] A forma da correção segue a política fixada no 082
- [ ] Nenhum outro ticket fechado tem critério de aceite em branco sem nota
