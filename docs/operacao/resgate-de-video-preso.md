# Resgate de Vídeo preso

Para quem recebeu um dos alertas de fila ou de Vídeo preso (`docker/observabilidade/alertas.yaml`).
O termo está no [`CONTEXT.md`](../../CONTEXT.md) § *Vídeo perdido*. A decisão é do
[ticket 107](../wayfinder/tickets/107-resgate-de-video-preso-pela-marca.md), e o mecanismo é o do
[ADR 0003](../adr/0003-reconciliacao-por-varredura.md).

**Resgate = apagar a marca de publicação.** A varredura do `videos` republica o comando pelo mesmo
caminho do envio. Ninguém move mensagem entre filas: isso não resgata o que foi descartado.

## 1. Achar o Vídeo

Pelo alerta de estado, no Postgres (`docker compose exec postgres psql -U fiapx -d fiapx_videos`):

```sql
-- Alerta "Video preso em PROCESSANDO"
SELECT id, iniciada_em, comando_publicado_em FROM video
WHERE estado = 'PROCESSANDO' ORDER BY iniciada_em;

-- Alerta "Video preso em RECEBIDO com a fila vazia"
SELECT id, recebido_em, comando_publicado_em FROM video
WHERE estado = 'RECEBIDO' AND comando_publicado_em IS NOT NULL ORDER BY comando_publicado_em;
```

Pelo alerta de fila, no management UI (`http://localhost:15672`), em *Get messages* com
*Ack mode: Nack message requeue true*. O `idVideo` está no corpo. Filas de fim de linha:
`extracao.extrair.estacionamento`, `videos.dlq` e `notificacao.dlq`.

Cuidado com o Estacionamento: ele é quorum, e cada leitura com requeue conta como entrega
devolvida. Sem `x-delivery-limit` declarado, vale o limite padrão do RabbitMQ 4 (20), e sem
dead-letter a mensagem que o atinge é descartada. Leia uma vez e anote os ids. As duas DLQs
clássicas não contam entregas.

Antes de resgatar, confirme que o Vídeo está parado mesmo. Um `PROCESSANDO` sob pico pode estar só
atrás do backlog ([ticket 113](../wayfinder/tickets/113-falso-positivo-de-video-preso-em-processando-sob-pico.md)).
Olhe o trace pelo `idVideo` e as filas de `extracao.extrair`.

## 2. Resgatar

```bash
scripts/resgata-video.sh <idVideo>
```

| Estado do Vídeo | O que o script faz | Saída |
|---|---|---|
| `RECEBIDO` ou `PROCESSANDO` | zera `comando_publicado_em`; a varredura republica `ExtrairVideo` | `0` |
| `FALHOU` sem `falha_publicada_em` | nada a zerar: ele já é pendente, e a varredura republica `VideoFalhou` | `0` |
| `FALHOU` com `falha_publicada_em` | recusa | `2` |
| `CONCLUIDO` | recusa | `2` |
| id inexistente | recusa | `3` |

A varredura roda a cada 30 s. O comando republicado é mensagem nova e começa uma nova série de
três tentativas. Acompanhe pela API ou pelo Postgres até o Vídeo chegar a `CONCLUIDO` ou `FALHOU`.

Enquanto a nova série roda, o alerta de `PROCESSANDO` **continua aceso**: o relógio dele é o
`iniciada_em` da primeira tentativa, e o resgate não o reescreve. Não repita o resgate só por isso.
Repetir é inofensivo, mas dobra a Extração. Espere pelo menos o pior caso de uma série, os ~21 min
derivados ao lado de `fiapx.deteccao.limiar-de-video-preso`, no `application.properties` do
`videos`.

## 3. Purgar a mensagem residual

**Depois** do resgate, a mensagem que ficou na fila de fim de linha é duplicata inofensiva: as
transições são idempotentes, e um evento que chegar a um Vídeo já terminal não muda nada. Ela
só suja o alerta de fila.

- Se todas as mensagens da fila são de Vídeos resgatados ou já terminais, purgue a fila inteira
  (management UI, *Purge Messages*, ou
  `curl -u fiapx:fiapx -X DELETE http://localhost:15672/api/queues/%2F/<fila>/contents`).
- Se há mensagem de Vídeo ainda não resgatado, resgate esse Vídeo antes. Purgar sem resgatar é
  perder a única pista que sobrou dele.

## O que o resgate não cobre

- **`FALHOU` com o aviso já publicado.** A mensagem em `notificacao.dlq` é o `VideoFalhou` de um
  Vídeo com marca, e o script recusa. O `videos` só sabe que publicou, não se o e-mail chegou.
- **Original apagado.** Se o arquivo enviado não existe mais no MinIO, a nova Extração não tem o
  que ler. Desde o ticket 105 o original não expira antes do desfecho
  ([ADR 0005](../adr/0005-retencao-do-original.md)); o que a regra antiga do bucket já apagou não
  volta.
