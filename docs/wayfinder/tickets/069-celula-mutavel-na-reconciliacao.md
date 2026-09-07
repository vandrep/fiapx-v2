# Célula mutável `new int[1]` na reconciliação

- id: 069
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7`, convertido em ticket com
aprovação do usuário.

## O problema

`ReconciliarPublicacoesPendentesUseCase` conta as publicações usando um `new int[1]` como célula
mutável, para escapar da exigência de efetivamente-final dentro da lambda. A forma funciona, mas
esconde estado compartilhado numa cadeia reativa: quem lê precisa provar sozinho que só uma
thread escreve ali.

É código do caminho da varredura do [ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) —
a rede de segurança que alcança o Vídeo cuja publicação se perdeu. Vale ser legível.

## O que entregar

A contagem expressa pelos operadores da própria cadeia, sem célula externa. O número contado e
o que ele alimenta (log, métrica ou retorno) saem idênticos — o ticket é de forma, não de
comportamento.

## Critérios de aceite

- [x] Nenhum array de um elemento como acumulador no use case
- [x] O valor reconciliado e o que é registrado a partir dele não mudam
- [x] `./mvnw test` verde a partir da raiz

## Resolução

A quantidade de comandos republicados agora percorre a própria cadeia de
`CompletableFuture`: depois de publicar os comandos em sequência, o estágio produz o tamanho
da lista e o entrega ao estágio que busca e publica as falhas. Esse valor continua compondo o
mesmo `Republicacoes`, consumido sem alteração pelo log do scheduler.

A ordem permanece comandos → falhas, com o mesmo instante de corte e o mesmo tamanho de lote.
O teste comportamental de `ReconciliarPublicacoesPendentesUseCase` passou isoladamente, e
`./mvnw test` passou a partir da raiz.
