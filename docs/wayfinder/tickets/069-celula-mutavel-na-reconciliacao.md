# Célula mutável `new int[1]` na reconciliação

- id: 069
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Nenhum array de um elemento como acumulador no use case
- [ ] O valor reconciliado e o que é registrado a partir dele não mudam
- [ ] `./mvnw test` verde a partir da raiz
