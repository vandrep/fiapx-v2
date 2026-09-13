# Limitações da conservação no documento de arquitetura

- id: 109
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)). Três pontos foram decididos como
**fora do modelo de falha** e precisam estar escritos, para que "nenhum Vídeo perdido" não seja
lido como promessa maior do que é.

## O que registrar em `docs/arquitetura.md`

1. **Perda de volume ou de nó.** O modelo de falha cobre crash de processo ou réplica, queda de
   rede, dependência fora do ar e bug que manda mensagem para fim de linha. Não cobre perda do
   volume do Postgres, do MinIO ou do RabbitMQ: no Compose, cada um é nó único, sem backup nem
   replicação. O que cobriria: cluster de broker, réplica do Postgres, MinIO distribuído ou
   backup.
2. **Sem cota por Dono.** Um único Dono pode ocupar a fila FIFO e fazer os outros esperarem. É
   equidade, não conservação: ninguém perde Vídeo.
3. **`POST /videos` sem idempotência.** Se a conexão cair depois do commit e antes da resposta,
   o cliente não sabe que o Vídeo foi criado e pode duplicá-lo ao reenviar. Parte dos 39 `502`
   do [ticket 028](028-escala-da-borda.md) pode estar nesse caso. Duplicar não é perder, e o
   Dono vê o Vídeo na listagem. O que cobriria: header `Idempotency-Key` com índice único.

Atualizar também a linha "Não perder requisição em pico" da tabela de requisitos, apontando
para o termo *Vídeo perdido* e para os tickets 103–108 conforme fecharem.

## Critérios de aceite

- [ ] As três limitações na seção de limitações do `docs/arquitetura.md`, cada uma com o que a
      cobriria.
- [ ] Linha da tabela de requisitos coerente com o termo do glossário.
- [ ] Linha em "Decisões até aqui" no mapa.
