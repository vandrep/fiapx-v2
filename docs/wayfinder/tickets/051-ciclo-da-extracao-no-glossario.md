# Ciclo da Extração ganha nome no glossário e superfície honesta

- id: 051
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...b4672ff`, convertido em ticket com
aprovação do usuário. Duas observações sobre a mesma peça. A primeira é regra documentada:
o AGENTS.md manda que todo termo de domínio usado em código passe pelo glossário canônico, e
"Ciclo da Extração" não está lá — o glossário tem Extração, tentativa, Pacote e
Estacionamento. A segunda é heurística de manutenção: parte dos métodos dessa peça só
traduz um booleano em um opcional, sem carregar regra própria, enquanto a classificação da
falha do ffmpeg e a validação da contagem de frames carregam. Não há defeito funcional.

## O que entregar

Quem lê o código da classificação de falha do `extracao` encontra cada termo no glossário e
uma superfície onde cada método existe por carregar regra. O comportamento de classificação
observado de fora — motivo da falha, permanente ou transitória — não muda.

## Condições de aceite

- [ ] O termo usado pelo tipo existe no glossário canônico, ou o tipo passa a usar
  vocabulário que já existe lá; a escolha registrada com a razão.
- [ ] Os métodos que só traduzem um booleano em um opcional saem do caminho, sem perder
  legibilidade no ponto de chamada.
- [ ] Os motivos de falha e a distinção entre permanente e transitória saem idênticos para
  cada entrada que a suíte hoje exercita.
- [ ] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.
