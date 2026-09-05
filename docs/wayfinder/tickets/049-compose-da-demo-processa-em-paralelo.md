# O Compose da demo processa mais de um vídeo ao mesmo tempo

- id: 049
- label: wayfinder:bug
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `3a3ec95...b4672ff`, convertido em ticket com aprovação
do usuário. O primeiro requisito funcional do enunciado é processar mais de um vídeo ao
mesmo tempo. A stack que o README manda subir e que o smoke exercita declara o `extracao`
sem réplicas, e o serviço limita as mensagens em voo a uma. Resultado observável: um vídeo
por vez. A concorrência existe só no overlay de carga, que é harness de medição. A
documentação de arquitetura já dá o requisito como atendido por réplicas independentes — a
arquitetura permite, a entrega não exerce.

## O que entregar

Quem sobe a stack seguindo o README e envia uma rajada de vídeos vê mais de um sendo
extraído ao mesmo tempo, sem precisar do harness de carga nem de flags que o README não
ensina. O requisito passa a ser demonstrável na stack que a banca abre.

## Condições de aceite

- [ ] A stack padrão do projeto processa vídeos concorrentemente, por réplicas do worker,
  por mensagens em voo, ou por ambos — a escolha registrada com a razão.
- [ ] Observar a concorrência de fora: uma rajada de envios produz extrações sobrepostas no
  tempo, e o resultado de cada vídeo continua correto e atribuído ao seu dono.
- [ ] Preservar as garantias que o limite de mensagens em voo protegia hoje, ou registrar
  o que muda e por que é aceitável.
- [ ] O README ensina como observar isso, sem depender do overlay de carga.
- [ ] O fluxo ponta-a-ponta contra o Compose continua passando.

## Dependências

Nenhuma. Pode começar imediatamente.
