# Unificar a forma dos três use cases de evento da extração

- id: 053
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 052
- prioridade: P3

## Origem

Possível Código Duplicado identificado no eixo Standards da revisão de `3a3ec95...b4672ff`,
convertido em ticket com aprovação do usuário. Os use cases que processam extração iniciada,
concluída e falhou repetem a mesma forma: busca o Vídeo, deixa a entidade decidir a
transição, curto-circuita quando ela recusa, grava. É heurística de manutenção: não há
defeito funcional, e a decisão de transição já mora na entidade, como o ADR 0002 exige.

## O que entregar

Os três caminhos de evento compartilham a forma comum, de modo que mudar a política de
curto-circuito ou de gravação deixe de exigir três edições em sincronia. Nenhum
comportamento observável muda: as mesmas transições são aceitas e recusadas, e o consumo de
evento repetido continua idempotente.

## Condições de aceite

- [ ] A forma comum aos três use cases passa a existir em um lugar só, sem mover a decisão
  de transição para fora da entidade e sem quebrar as regras de camada.
- [ ] Cada um dos três eventos continua aceitando e recusando exatamente as mesmas
  transições de hoje, inclusive as recusadas por estado incompatível.
- [ ] Evento repetido continua não produzindo efeito adicional.
- [ ] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Bloqueado pelo ticket 052: os dois reescrevem as mesmas assinaturas do caminho da conclusão,
e fazer o 053 antes obrigaria a refazer a forma comum logo depois.
