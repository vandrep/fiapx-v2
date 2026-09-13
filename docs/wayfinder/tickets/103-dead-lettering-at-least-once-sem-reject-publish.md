# O dead-lettering at-least-once que não está ligado

- id: 103
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-13, SHA inicial 689b7d9)
- bloqueado-por: 110
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
- [x] `scripts/persistencia-rabbitmq.sh` e `scripts/smoke.sh` verdes contra o Compose atualizado.
- [x] Emenda no ADR 0001 e linha em "Decisões até aqui" no mapa.

## Andamento (2026-09-13)

Implementado em 2026-09-13 sobre `develop @ 689b7d9`. O ticket **continua aberto**: o critério
dos dois scripts verdes não foi cumprido, pelo motivo de *Validação*, abaixo. O mantenedor
escolheu corrigir antes o passo 12 do smoke, no
[110](110-passo-12-do-smoke-antes-da-segunda-exportacao.md), e fechar este depois.

A policy `dead-letter-at-least-once` do `docker/rabbitmq/definitions.json` passou a definir
`"overflow": "reject-publish"` ao lado de `dead-letter-strategy`. Mais nada mudou no código.

### O que a documentação do RabbitMQ diz

A página *Quorum Queues* diz que at-least-once exige `dead-letter-strategy=at-least-once` **e**
`overflow=reject-publish`. Com o `drop-head` default, *"even if a queue length limit is not
set"*, o broker volta a at-most-once. `overflow` é chave de policy aceita por quorum queue, e a
estratégia troca dinamicamente numa fila existente, então **não é preciso recriar fila**.

A troca que perde mensagem é a de **saída**: de `at-least-once` para `at-most-once`, ou de
`reject-publish` para `drop-head`. Ela apaga as dead-lettered ainda sem confirmação do destino.
Este ticket fez a troca de entrada, que não descarta nada. O risco de saída ficou registrado no
ADR 0001.

### Verificação no broker

Subi o Compose com o volume antigo `fiapx-v2_fiapx-rabbitmq-data`, sem recriar filas, e rodei
`rabbitmqctl list_queues name type policy effective_policy_definition arguments`. As sete filas
quorum trazem `[{<<"dead-letter-strategy">>, at-least-once}, {<<"overflow">>, reject-publish}]`:
`extracao.extrair`, `extracao.extrair.dlq`, `extracao.extrair.estacionamento`, as três
`videos.extracao-*` e `notificacao.video-falhou`.

As duas classic, `videos.dlq` e `notificacao.dlq`, não mostram policy nenhuma. A causa foi
medida com uma policy temporária `t103-sonda`, de prioridade 5 e casando só `^videos\.dlq$`,
removida depois. Com `{"overflow":"reject-publish"}` ela casou. Com os dois campos, a fila
voltou a `#{}`. No RabbitMQ 4.3, uma chave que a classic não suporta, aqui
`dead-letter-strategy`, faz a policy inteira deixar de casar com ela. A policy antiga tinha a
mesma chave, então isso já devia valer para ela, mas não a sondei à parte. Sem limite de
tamanho não há efeito, porque o regime de dead-lettering é da fila de origem.

### Efeitos colaterais

- **`reject-publish` não dispara hoje.** Nenhuma fila tem `x-max-length` nem
  `x-max-length-bytes`: os `arguments` acima não trazem nenhum dos dois, e a policy é única.
- **Mensagem dead-lettered fica viva na origem até o destino confirmar**, e pode chegar duplicada.
- Dev Services seguem *at-most-once*, como o ADR 0001 já assumia.

### Validação

- **`scripts/smoke.sh`, contra o Compose padrão.** A primeira rodada, com a stack recém-subida,
  **reprovou no passo 12**, no painel *Borda — recusas do contrato (4xx), por status*. O fluxo e
  os passos 1–11 passaram. A segunda rodada, com a stack quente, **passou** (`exit=0`), e o
  painel devolveu 12 amostras.
- **`scripts/persistencia-rabbitmq.sh`.** O ensaio próprio passou: os 3 comandos foram
  preservados depois de recriar o broker, e marcas, filas, bindings e **policies** saíram
  idênticos. O smoke que o script encadeia no fim roda numa stack isolada e nova, e **reprovou
  no mesmo passo e no mesmo painel**. O script terminou com `exit=1`.
- **Por que o painel reprova numa stack fria.** Medido depois, no
  [110](110-passo-12-do-smoke-antes-da-segunda-exportacao.md). O `videos` exporta métrica a cada
  60 s, e numa stack recém-criada o smoke chega ao passo 12 antes de duas exportações; o
  `rate()` do painel precisa de duas amostras. Com o `definitions.json` de `689b7d9`, ou seja,
  com a **policy antiga**, o passo reprova igual. O defeito é anterior a este ticket. A primeira
  versão desta seção atribuía a falha ao restart da observabilidade no passo 11. Estava errada:
  o restart preserva as amostras.
- **`./mvnw test` não rodou.** Não mudou código Java nem `application.properties`, e os Dev
  Services não leem `definitions.json`.
- **`/code-review 689b7d9`, padrões e spec.** Foram acatados:
  - a afirmação invertida sobre a direção da troca que descarta;
  - a causa das DLQs classic sem policy, agora medida;
  - o `assignee` vazio;
  - a emenda que desmentia o "só em failover" sem dizer;
  - a verificação operacional duplicada no ADR;
  - o fechamento com critério desmarcado.

  Deixados de fora: a grafia `at-most-once` em código ou itálico, o verbo no assunto do commit e o
  link para *Vídeo perdido*. O termo está no `CONTEXT.md` do working tree, fora deste commit,
  junto com a série 104–109.

## Resolução

Fechado em 2026-09-13, depois do [110](110-passo-12-do-smoke-antes-da-segunda-exportacao.md). Com a espera do
passo 12, `scripts/smoke.sh` passou contra a stack recém-criada e contra a stack em uso, e
`scripts/persistencia-rabbitmq.sh` passou inteiro (`exit=0`). As três corridas usaram a policy
deste ticket. Antes delas, o broker foi reiniciado para recarregar o `definitions.json` com
`overflow=reject-publish`, e as 7 filas quorum foram conferidas com o campo.
