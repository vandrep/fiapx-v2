# `framework/web/` nos dois workers que não têm borda HTTP

- id: 068
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7` (Mysterious Name), convertido em
ticket com aprovação do usuário.

## O problema

`ExtracaoConfiguration` e `NotificacaoConfiguration` moram em `framework/web/`. O AGENTS.md é
explícito em dizer que esses dois serviços **não têm borda HTTP** — a tabela "O que difere entre
os três serviços" traz "nenhuma" na linha da borda para os dois, e o BDD entra neles por
mensageria, não por RestAssured.

O pacote, portanto, anuncia uma camada que o serviço não tem, e um leitor novo procura ali o
`Resource` que nunca vai existir. O nome vem do template, não de uma decisão.

## O que entregar

As duas classes num pacote que descreva o que elas são. Uma pergunta antes de escolher o nome:
o que essas classes configuram é borda de entrada nenhuma — decidir isso é decidir o pacote.

O `ArchitectureConstraintsTest` é a autoridade sobre camadas e ele conhece esses pacotes: leia
o que ele cobra sobre `framework` antes de mover, e se a regra precisar acompanhar, edite as
**três cópias**, com `scripts/verifica-testes-arquiteturais.sh` verde. O `videos` tem borda HTTP
de verdade e não deve ser tocado.

## Critérios de aceite

- [x] Nenhum pacote `web` nos serviços que não têm borda HTTP
- [x] O teste arquitetural continua cobrando a camada `framework` para as classes movidas
- [x] As três cópias do teste seguem idênticas; `./mvnw test` verde a partir da raiz

## Resolução

`ExtracaoConfiguration` e `NotificacaoConfiguration` são raízes de composição CDI: conhecem
o grafo de objetos e produzem os controllers, mas não constituem uma borda de entrada. As duas
foram movidas para `framework.configuration`; o `framework.web` do `videos`, que de fato contém
a borda HTTP pública, não mudou.

O `ArchitectureConstraintsTest` ganhou uma regra que proíbe qualquer pacote
`framework.web` nos serviços diferentes de `videos`. A regra deriva o serviço pelo mesmo
`MODULO_DO_SERVICO` já usado pelo teste, portanto suas três cópias continuam byte a byte
idênticas e as classes movidas seguem sob a camada `framework` cobrada pela regra de layout.

`scripts/verifica-testes-arquiteturais.sh` e `./mvnw test` passaram a partir da raiz; a suíte
executou 140 testes em `videos`, 277 em `extracao` e 29 em `notificacao`.
