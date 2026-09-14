# Detecção de Vídeo preso pelo estado, não pelas filas

- id: 106
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial 94e0d90)
- bloqueado-por: 105
- prioridade: P2

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)). **Reabre** a decisão do
[ticket 033](033-iniciada-em-morto.md), que removeu o `iniciadaEm` do caminho interno por falta
de medição que justificasse varrer `PROCESSANDO` preso.

## O problema

Os alertas de `docker/observabilidade/alertas.yaml` olham **filas**: Estacionamento, DLQ do
`extracao` e fila com mensagem e zero consumidores. Um Vídeo pode ficar sem desfecho sem
mensagem nenhuma parada: um bug que dá `ack` sem transicionar, ou uma mensagem descartada por
dead-lettering sem garantia (ver [103](103-dead-lettering-at-least-once-sem-reject-publish.md)).
Só o Postgres sabe disso. Sem o instante de início, nada distingue um `PROCESSANDO` legítimo de
um preso.

## Decisão

Alerta por estado, com dois critérios:

- **`PROCESSANDO` há mais de 30 min desde o início da Extração.** O instante de início volta a
  ser gravado como coluna. A justificativa que faltou ao 033 agora é medível: o pior caso de
  uma tentativa gira em torno de 6 min (ffprobe 30 s, ffmpeg 300 s, transferências), e três
  entregas somam cerca de 20 min.
- **`RECEBIDO` com comando publicado há mais de 30 min, enquanto `extracao.extrair` está sem
  mensagens prontas.** Durante um pico legítimo, o alerta espera o backlog drenar. Detecção
  atrasada é segura porque, depois do [105](105-original-so-expira-depois-do-desfecho.md), o
  original não expira enquanto isso.

Recusado: resgate automático (republicaria no pico, a armadilha que o ADR 0003 já recusou),
limite único por idade desde o envio (dispara em todo pico) e só painel sem alerta.

## Critérios de aceite

- [x] Coluna do instante de início no `init.sql`, gravada na transição para `PROCESSANDO`,
      com `validate` passando. O campo continua no contrato de mensagens, sem mudança.
- [x] Métrica de Vídeos presos pelos dois critérios, exportada de forma que o Grafana a avalie.
- [x] Alerta provisionado em `alertas.yaml`, com a expressão verificada contra dados reais,
      como os três atuais.
- [x] Os 30 min derivados dos timeouts em `extracao/src/main/resources/application.properties`,
      escritos junto do número.
- [x] Linha em "Decisões até aqui" no mapa registrando a reversão do 033, e ponteiro de uma
      linha no fim do 033 para este ticket, conforme `TRACKER.md`.
- [x] `./mvnw test` e `scripts/smoke.sh` verdes.

## Resolução

Implementado em 2026-09-14 sobre `develop @ 94e0d90`.

**Coluna.** `iniciada_em TIMESTAMPTZ` no `init.sql`, com `CHECK (estado <> 'PROCESSANDO' OR
iniciada_em IS NOT NULL)` e dois índices parciais para as contagens. O instante é o `iniciadaEm`
do evento, gravado pelo mesmo `UPDATE` condicional que tira a linha de `RECEBIDO`: a reentrega não
o reescreve, e o relógio é o da primeira tentativa. `Video.marcaComoIniciada` recusa nulo. O
controller usa o instante do consumo quando o evento chega sem o campo, para que o evento não vire
`nack`. O contrato não mudou; `docs/contratos/mensagens.md` ganhou um parágrafo sobre o uso.

**Métrica.** `fiapx.videos.presos`, gauge do `videos` com `estado` em `PROCESSANDO`/`RECEBIDO`. O
`VideosPresosScheduler` conta o Postgres a cada minuto pelo `ContarVideosPresosUseCase`. Sem
amostra, a métrica não exporta série: vale antes da primeira contagem e depois de uma que falhou.
O limiar é `fiapx.deteccao.limiar-de-video-preso=30m`, com a derivação ao lado, no
`application.properties` do `videos`. A condição "fila sem mensagem pronta" do `RECEBIDO` mora no
alerta, porque só o Prometheus enxerga o broker e o banco juntos. O ADR 0004 ganhou uma seção
dizendo por que esta métrica não reabre a recusa de *Vídeos por estado*. O `AGENTS.md` passou a
contar duas métricas próprias.

**Alertas.** Regras 4 e 5 em `alertas.yaml`, grupo `videos-presos`: `max(...{estado="PROCESSANDO"})
> 0`, e `max(...{estado="RECEBIDO"}) > 0 and on () (sum(rabbitmq_detailed_queue_messages_ready{queue="extracao.extrair"}) == 0)`.

**Deploy.** O `init.sql` só roda em volume vazio. O README traz a migração aditiva, que tem de
rodar **com o `videos` parado**: a imagem nova não sobe sem a coluna (`validate`), e a antiga
violaria o `CHECK`. A migração preenche `iniciada_em` com `recebido_em` nos `PROCESSANDO`
existentes, e por isso um `PROCESSANDO` antigo acende o alerta logo após o upgrade. É intencional:
detecta cedo, nunca tarde. Aplicada no volume local, que só tinha terminais.

### Validação

- Testes novos:
  - `VideoTest` (+3): guarda o instante, reentrega não troca, nulo recusado.
  - `ProcessarExtracaoIniciadaUseCaseTest` (+1).
  - `ContarVideosPresosUseCaseTest` (5).
  - `VideoDataSourceAdapterTest` (+1): as duas contagens em HQL contra o Postgres.
  - `MetricaDeVideosPresosTest` (2).
  - `InicioDaExtracaoPelaBordaTest` (2), pelo RabbitMQ real. Por mutação, reprovou com o consumidor
    ignorando o campo.
- `./mvnw test` na raiz: 501 testes verdes (182 `videos`, 290 `extracao`, 29 `notificacao`) antes
  da revisão, e verde de novo depois das correções dela.
- `init.sql` subido num Postgres zerado dá o mesmo esquema do volume migrado; só a posição da coluna
  difere. O `videos` subiu em `%prod` com `validate`.
- `scripts/smoke.sh` verde com a imagem do `videos` construída localmente. Os três Vídeos do smoke
  ficaram com `iniciada_em` entre `recebido_em` e `finalizado_em`.
- **Expressões contra dados reais**, na stack do Compose, com três linhas plantadas e apagadas
  depois:
  - As linhas: `PROCESSANDO` iniciado há 31 min, `PROCESSANDO` há 29 min e `RECEBIDO` com comando
    publicado há 31 min.
  - A série `fiapx_videos_presos` foi a 1/1 cerca de 90 s depois da inserção. Foi a 2 em
    `PROCESSANDO` quando a linha de 29 min cruzou o limiar.
  - As duas regras passaram de `inactive` a `pending` e depois a `firing`, com a anotação
    renderizando a contagem.
  - Com o `extracao` parado e uma mensagem pronta em `extracao.extrair`, a regra 5 voltou a
    `inactive` enquanto a 4 seguiu `firing`.

### Limites que ficam

- **Falso positivo em `PROCESSANDO` sob pico, não medido.** Os 21 min de três tentativas supõem
  entregas seguidas. Uma entrega devolvida por crash de réplica espera atrás do backlog, e os 420 s
  por tentativa não são teto duro, porque o I/O não tem timeout. O alerta erra para o lado de
  chamar um humano. Está escrito junto do número e no alerta.
- **Falso positivo em `RECEBIDO`** com `videos.extracao-iniciada` atrasada: a Extração já roda e a
  fila de comando está vazia. Não é Vídeo perdido.
- **Silêncios.** A regra 5 cala com o RabbitMQ fora e durante backlog; ela resolve se um backlog
  aparecer com ela disparada. As duas calam com o `videos` fora (`noDataState: OK`), como os
  outros três alertas.
- **Latência.** Entre o Vídeo cruzar o limiar e o alerta disparar, são até ~3 min: amostra,
  exportação e `for: 1m`.
- **Sem guarda no smoke.** Um rename da métrica deixaria as regras 4 e 5 em silêncio, e o passo
  12 só lê as queries do painel.
