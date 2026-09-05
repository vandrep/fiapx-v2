# Nomear o resultado da extração dentro do videos

- id: 052
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Possíveis Aglomerado de Dados e Nome Misterioso identificados no eixo Standards da revisão
de `3a3ec95...b4672ff`, convertidos em ticket com aprovação do usuário. Os quatro dados da
conclusão — instante, chave do Pacote, quantidade de frames e tamanho do Pacote — viajam
juntos por cinco assinaturas, do consumidor até o gateway: é um tipo querendo nascer, e o
`extracao` já batizou metade dele. No mesmo caminho, `tamanhoBytes` significa o tamanho do
Pacote no consumidor e no controller, e o tamanho do vídeo na entidade; só o comando
intermediário desambigua. São heurísticas de manutenção, não defeito atual.

## O que entregar

O caminho da conclusão da extração dentro do `videos` carrega um conceito nomeado em vez de
quatro parâmetros soltos, e nenhum nome ao longo dele significa duas coisas diferentes.
Envio, consulta e listagem continuam produzindo a mesma representação pública, e o contrato
de mensagem não muda.

## Condições de aceite

- [ ] Os dados da conclusão passam a viajar como um conceito só pelas camadas onde hoje
  viajam soltos, respeitando as regras arquiteturais de cada camada.
- [ ] Nenhum nome no caminho da conclusão fica ambíguo entre tamanho do Pacote e tamanho do
  vídeo, inclusive na fronteira de mensageria.
- [ ] O contrato de mensagens permanece intacto: nomes e tipos dos campos publicados e
  consumidos não mudam.
- [ ] Verificar pela borda que um Vídeo concluído expõe instante, quantidade de frames e
  tamanho do Pacote com os mesmos valores de hoje.
- [ ] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.
