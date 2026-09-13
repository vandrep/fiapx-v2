# Detecção de Vídeo preso pelo estado, não pelas filas

- id: 106
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Coluna do instante de início no `init.sql`, gravada na transição para `PROCESSANDO`,
      com `validate` passando. O campo continua no contrato de mensagens, sem mudança.
- [ ] Métrica de Vídeos presos pelos dois critérios, exportada de forma que o Grafana a avalie.
- [ ] Alerta provisionado em `alertas.yaml`, com a expressão verificada contra dados reais,
      como os três atuais.
- [ ] Os 30 min derivados dos timeouts em `extracao/src/main/resources/application.properties`,
      escritos junto do número.
- [ ] Linha em "Decisões até aqui" no mapa registrando a reversão do 033, e ponteiro de uma
      linha no fim do 033 para este ticket, conforme `TRACKER.md`.
- [ ] `./mvnw test` e `scripts/smoke.sh` verdes.
