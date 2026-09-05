# Retry no acesso ao MinIO pela borda do videos

- id: 048
- label: wayfinder:bug
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Standards da revisão de `3a3ec95...b4672ff`, convertido em ticket com
aprovação do usuário. O ADR 0001 justifica a política de falhas afirmando que blips de I/O
de segundos contra MinIO e Postgres são cobertos por `@Retry` no adapter. O `extracao` e o
`notificacao` cumprem, cada um com um bean de cliente isolado. O `videos` não: a extensão de
fault tolerance nem sequer está declarada no módulo, e o adapter de arquivo sobe e baixa do
MinIO sem nenhuma proteção. É divergência entre o ADR e o código, não defeito medido.

## O que entregar

Uma instabilidade curta do armazenamento durante o envio ou o download deixa de virar erro
interno para quem chamou a API. Ou o caminho síncrono da borda passa a se comportar como o
que o ADR 0001 descreve, ou o ADR passa a declarar por escrito que a borda é exceção
deliberada — e diz por quê.

## Condições de aceite

- [ ] Decidir entre implementar o retry na borda ou registrar a exceção no ADR 0001, com a
  razão explícita no documento.
- [ ] Se implementado: aplicar a proteção no ponto de fronteira com o armazenamento,
  respeitando as regras de camada e o isolamento de bean que os outros dois serviços já usam.
- [ ] Se implementado: verificar que uma falha transitória do armazenamento durante o envio
  não chega ao chamador como erro interno, e que uma falha persistente continua chegando.
- [ ] Preservar os status e corpos previstos no contrato HTTP para o envio e o download.
- [ ] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.
