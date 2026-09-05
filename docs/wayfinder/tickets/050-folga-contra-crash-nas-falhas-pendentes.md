# Folga contra crash também nas falhas pendentes da reconciliação

- id: 050
- label: wayfinder:bug
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...b4672ff`, convertido em ticket com aprovação
do usuário. A varredura de publicações pendentes aplica uma folga de tempo ao buscar
comandos pendentes e nenhuma folga ao buscar falhas pendentes. A folga existe para não
confundir publicação em voo com publicação perdida, que é a janela descrita no ADR 0003.
Sem ela, uma varredura que caia sobre um processamento de falha em voo republica o evento e
duplica a notificação. O ADR 0001 aceita entrega ao menos uma vez, então não há quebra de
contrato; a assimetria é que não tem explicação em documento nenhum.

## O que entregar

As duas metades da varredura tratam a janela entre gravar e publicar do mesmo jeito. Uma
varredura concorrente com um processamento de falha em voo deixa de gerar notificação
duplicada por essa causa — ou a assimetria fica registrada como decisão, com a razão.

## Condições de aceite

- [ ] Decidir entre simetrizar a folga ou documentar a assimetria, e registrar a escolha
  junto do ADR 0003.
- [ ] Se simetrizado: a busca de falhas pendentes passa a respeitar a mesma janela de
  proteção que a busca de comandos.
- [ ] Verificar que uma varredura disparada durante uma publicação de falha em voo não
  republica o evento.
- [ ] Verificar que uma falha realmente perdida continua sendo alcançada pela varredura,
  sem regressão do que o ADR 0003 garante.
- [ ] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.
