# Limitações da conservação no documento de arquitetura

- id: 109
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial bdee88f)
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

- [x] As três limitações na seção de limitações do `docs/arquitetura.md`, cada uma com o que a
      cobriria.
- [x] Linha da tabela de requisitos coerente com o termo do glossário.
- [x] Linha em "Decisões até aqui" no mapa.

## Resolução

Escrito em 2026-09-14 sobre `develop @ bdee88f`. Só documentação.

**Limitações.** Três itens novos em `docs/arquitetura.md` § *Limitações conhecidas*, logo depois
do que trata da borda e do 028, cada um terminando no que o cobriria. Três coisas vão além da letra:

- o item de volume inclui o Keycloak. Perdido esse volume, um Dono recriado ganha outro `sub` e
  deixa de ver os próprios Vídeos. O `fiapx-uploads` ficou de fora, porque o que está nele ainda
  não foi aceito;
- o item de volume diz que a fila quorum não replica com um nó só, e a linha "Broker reinicia" de
  § *O que impede a perda* deixou de chamá-la de "replicada";
- o item de idempotência diz que ninguém conferiu duplicatas no Postgres na rodada do 028. Diz também
  que a falta de idempotência é o motivo de o `non_idempotent` do nginx não ter sido ligado, o que o
  documento já registrava em § *quarta medição*.

"Mensagem para fim de linha" virou "mensagem para o Estacionamento", que é o termo do glossário.
As saídas da cota por Dono, cota na borda ou fila justa, são candidatas e não decisão.

**Requisitos.** A linha "Não perder requisição em pico" abre pelo *Vídeo perdido* do
`CONTEXT.md`, cita 103 a 108, todos fechados, e aponta as três limitações. Ela dizia que a borda
com réplicas "reduz a perda" para 9,75%. Pelo glossário, esses 39 `502` não são Vídeo perdido: ou
o envio não foi aceito, ou foi e chegou a desfecho, porque a rodada terminou com zero presos. O
título da limitação da borda trocou "não zera a perda" por "não zera os envios que falham".

**Revisão.** Veio do `/code-review` contra `bdee88f`. O Keycloak, a palavra "replicadas", o título
da borda e o critério "chegou a desfecho", no lugar de "aparece na listagem", saíram dela.

**Fora.** § *Escalar* e § *quarta medição* ainda chamam os 39 de "recusados". É vocabulário da
medição, do lado do cliente, e não foi reescrito. Pelo glossário, "recusa" é explícita, e um `502`
não é.
