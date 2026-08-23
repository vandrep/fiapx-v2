# Documentacao de arquitetura

- id: 023
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 022

## Question

O enunciado pede "documentacao da arquitetura proposta" como entregavel, e o video de 10
minutos tem de apresenta-la. O repositorio ja documenta muito — tres ADRs, dois contratos,
`AGENTS.md`, `CONTEXT.md`, quatro pesquisas, o mapa inteiro — mas **nada disso e a visao de
conjunto**: cada peca responde uma pergunta local, e a banca chega sem contexto nenhum.

O que falta decidir: qual o artefato (C4 ate que nivel? diagrama de sequencia do fluxo
assincrono? so prosa com o ASCII que ja esta no README?), onde ele vive
(`docs/arquitetura.md`? uma secao do README?), como os diagramas sao produzidos (Mermaid
versionado, imagem exportada, ASCII) e — o mais importante — **o que ele diz que os
documentos existentes nao dizem**, ja que a disciplina do repo e apontar, nunca duplicar.
Vale tambem decidir se as alternativas recusadas com registro (presigned URL, outbox
canonico, JavaCV, Kubernetes) entram, e em que profundidade: elas sao o que separa
"escolhemos X" de "sabemos por que nao Y".
