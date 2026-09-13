# O dead-lettering at-least-once que não está ligado

- id: 103
- label: ready-for-agent
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, a respeito da
premissa de que nenhum Vídeo seja perdido (ver *Vídeo perdido* no
[`CONTEXT.md`](../../../CONTEXT.md)). Faz parte da série 103–109, que saiu dessa sessão.

## O defeito

O [ADR 0001](../../adr/0001-politica-de-falhas.md) rejeitou filas classic porque o
dead-lettering delas é *at-most-once* e pode perder exatamente a mensagem de falha definitiva.
A policy `dead-letter-at-least-once` do `docker/rabbitmq/definitions.json` define só
`dead-letter-strategy`. A pesquisa do próprio repositório
([`rabbitmq-retry-dlq.md`](../../pesquisa/rabbitmq-retry-dlq.md), linhas 533 e 897) registra
que a estratégia exige também `overflow=reject-publish`; sem isso, o broker volta para
*at-most-once*.

Verificado no broker do Compose em execução, na sessão de origem:

```
rabbitmqctl list_queues name type policy effective_policy_definition arguments
```

Todas as filas quorum mostram `[{<<"dead-letter-strategy">>, at-least-once}]` como política
efetiva e **nenhuma** tem `overflow`, nem por policy nem por argumento. Portanto o sistema
entrega hoje o regime que o ADR 0001 diz ter recusado.

## Escopo

1. Ligar `overflow=reject-publish` onde a estratégia at-least-once deve valer, por policy do
   broker, junto da estratégia. Confirmar na documentação do RabbitMQ 4.3 a forma exata e se
   as filas precisam ser recriadas.
2. Confirmar pelo mesmo `rabbitmqctl` que a política efetiva passou a trazer os dois campos.
3. Verificar o efeito colateral: com `reject-publish`, fila cheia recusa publicação em vez de
   descartar. Hoje não há `x-max-length`, então não deve disparar; registrar isso.
4. Emendar o ADR 0001, na seção *Consequences* sobre a policy, com o que faltava e com a
   verificação. Não reescrever o texto anterior; acrescentar a emenda como as outras.
5. Dev Services continuam *at-most-once*, já assumido no ADR 0001; não mudar.

## Critérios de aceite

- [x] Política efetiva de todas as filas quorum com `dead-letter-strategy=at-least-once` e
      `overflow=reject-publish`, conferida no broker do Compose.
- [ ] `scripts/persistencia-rabbitmq.sh` e `scripts/smoke.sh` verdes contra o Compose atualizado.
- [x] Emenda no ADR 0001 e linha em "Decisões até aqui" no mapa.

## Resolução

A policy `dead-letter-at-least-once` do `docker/rabbitmq/definitions.json` passou a definir
`"overflow": "reject-publish"` ao lado de `dead-letter-strategy`. Mais nada mudou no código.

### O que a documentação do RabbitMQ diz

Pela página *Quorum Queues*, at-least-once exige `dead-letter-strategy=at-least-once` **e**
`overflow=reject-publish`. Com o `drop-head` default, *"even if a queue length limit is not set"*,
o broker volta a at-most-once. `overflow` é chave de policy aceita por quorum queue, e a estratégia
troca dinamicamente numa fila existente, então **não é preciso recriar fila**. Na troca, as
mensagens dead-lettered ainda sem confirmação do destino são descartadas. Essa janela só existe
durante a troca, e a troca é o boot do broker, que carrega o `definitions.json`.

### Verificação no broker

Subi o Compose com o volume `fiapx-v2_fiapx-rabbitmq-data` antigo, sem recriar filas, e rodei
`rabbitmqctl list_queues name type policy effective_policy_definition arguments`. As sete filas
quorum trazem `[{<<"dead-letter-strategy">>, at-least-once}, {<<"overflow">>, reject-publish}]`:
`extracao.extrair`, `extracao.extrair.dlq`, `extracao.extrair.estacionamento` e as três
`videos.extracao-*`, mais `notificacao.video-falhou`. As duas classic, `videos.dlq` e
`notificacao.dlq`, não mostram policy nenhuma. São destino terminal, e o regime vale na fila de
origem.

### Efeitos colaterais

- **`reject-publish` não dispara hoje.** Nenhuma fila tem `x-max-length` nem
  `x-max-length-bytes`: os `arguments` acima não trazem nenhum dos dois, e a única policy é esta.
- **Dead-lettered fica viva na origem até o destino confirmar.** Se a DLX não existe, se não há
  rota ou se o destino não confirma, a mensagem é retida e retentada, e pode chegar duplicada.
  Está registrado no ADR 0001.
- Dev Services seguem *at-most-once*, como o ADR 0001 já assumia.

### Validação

- `scripts/smoke.sh` contra o Compose padrão. A **primeira rodada, com a stack recém-subida,
  reprovou no passo 12**, no painel *Borda — recusas do contrato (4xx), por status*. O fluxo
  inteiro e os passos 1–11 passaram. A **segunda rodada, com a stack já quente, passou**
  (`exit=0`), e aquele painel devolveu 12 amostras.
- `scripts/persistencia-rabbitmq.sh`: o ensaio próprio passou. Os 3 comandos foram preservados
  depois de recriar o broker, e as marcas, as filas, os bindings e as **policies** saíram
  idênticos. O smoke que o script encadeia no fim, numa stack isolada e nova, **reprovou no mesmo
  passo 12 e no mesmo painel**. O critério de aceite dos dois scripts verdes fica **não cumprido
  como escrito**.
- Por que o painel reprova numa stack fria: os contadores `http_server_request_duration_seconds_count`
  de 401, 404 e 409 existem, nascem com valor 1 e ficam só com duas amostras, porque o passo 11
  para e sobe de novo a observabilidade. Nessa situação o `rate(...[5m])` sai vazio. Numa stack
  com tráfego anterior o contador sobe, e o painel tem série. A consulta é métrica HTTP do
  `videos` e não passa pelo broker. **Não medi com a policy antiga**, então "anterior a este
  ticket" é inferência pelo mecanismo, e não medição.
- `./mvnw test` não rodou: nenhum código Java nem `application.properties` mudou, e os Dev
  Services não leem `definitions.json`.
