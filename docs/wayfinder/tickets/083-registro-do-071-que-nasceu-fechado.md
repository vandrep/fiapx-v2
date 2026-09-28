# O ticket 071 nasceu fechado, com os critérios de aceite em branco

- id: 083
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-08)
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

- [x] O 071 não fica mais como único ticket fechado com aceite em branco e sem explicação
- [x] A forma da correção segue a política fixada no 082
- [x] Nenhum outro ticket fechado tem critério de aceite em branco sem nota

## Resolução

A política do [082](082-politica-de-reescrita-de-ticket-fechado.md) fechou a primeira das duas
saídas que este ticket previa: marcar as caixas do 071 é reescrever corpo de ticket fechado, e o
`TRACKER.md` § *O que pode mudar num ticket `fechado`* não permite. Ficou a segunda, na forma que
a mesma regra oferece — **seção nova no fim do arquivo**. O 071 ganhou uma `## Correção (083)`
dizendo que nasceu fechado, em `0e30a5e`, no mesmo commit que executou o
[074](074-remover-o-ferramental-de-agente-versionado.md), e onde cada um dos três critérios foi
atendido: dois pela entrega do 074, e o terceiro por perda de objeto — não sobrou arquivo sob
`.agents/` para explicar.

**A premissa de que o 071 era o único assim estava errada.** Entre os 84 tickets fechados, cinco
têm critério de aceite em branco: 053, 057, 058, 059 e 071. Os 057, 058 e 059 nasceram fechados
pelo mesmo mecanismo do 071 — arquivo criado no commit que os implementou, já com `status:
fechado` e `assignee` —, e o 053 nasceu aberto e fechou com as caixas por marcar. Os quatro
receberam a mesma `## Correção (083)`, porque o terceiro critério deste ticket cobra que nenhum
fechado fique com caixa em branco *sem nota*, e não só o 071. O [078](078-regras-do-teste-arquitetural-sem-linha-no-agents.md)
já tinha a sua, escrita na linha da própria caixa pela sessão que o fechou.

O levantamento achou uma coisa que a nota do 071 não teria achado sozinha: **o oitavo critério do
057 não foi atendido**. A resolução dele diz, e sempre disse, que o `smoke.sh` e o ensaio de
conservação não rodaram, com o motivo. Caixa em branco por esquecimento e caixa em branco por
critério não atendido pareciam a mesma coisa no registro; agora não parecem.

O que **não** se fez, e é decisão de mantenedor: nada no `TRACKER.md` obriga a marcar as caixas
antes de fechar, nem proíbe criar ticket já fechado. Os dois hábitos produziram os cinco casos
acima, e continuam permitidos. Corrigir isso é mudar a convenção, que é o escopo do 082 — já
fechado —, não o deste ticket.
