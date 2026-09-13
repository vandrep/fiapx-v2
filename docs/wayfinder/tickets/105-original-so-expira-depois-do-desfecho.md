# O original só expira depois do desfecho

- id: 105
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)). Reverte em parte a decisão do
[ticket 011](011-limites-operacionais.md) registrada no mapa: retenção por ciclo de vida do
MinIO, "zero código".

## O problema

`docker/minio/seed.sh` aplica `mc ilm rule add --expire-days 7` aos buckets `videos` **e**
`pacotes`. O original expira 7 dias após o envio, qualquer que seja o estado do Vídeo. Um Vídeo
parado em DLQ, no Estacionamento ou num backlog de mais de 7 dias perde o arquivo de origem, e
nenhum resgate o recupera: é Vídeo perdido na terceira forma do glossário.

## Decisão

- Objetos do bucket `videos` só expiram depois de **marcados** (por exemplo, tag de objeto)
  quando o Vídeo chega a `CONCLUIDO` ou `FALHOU`. Regra de ciclo de vida filtrada pela marca.
- A regra de ciclo de vida conta a partir da **criação** do objeto, não da marca. A janela
  depois do desfecho varia de 0 a 7 dias; aceito, porque o Dono só baixa o Pacote. Confirmar
  na sessão que o MinIO fixado suporta filtro por tag.
- **Sem limite de segurança** para objetos não marcados: é o que torna "nunca" literal.
- Se o `INSERT` do `EnviarVideoUseCase` falhar depois do upload, o objeto órfão é apagado na
  base do melhor esforço.
- Vazamento residual aceito e documentado: crash entre o `UPDATE` terminal e a marcação, ou
  falha da limpeza do órfão, deixa um objeto para sempre. É vazamento, não perda.
- Bucket `pacotes` inalterado.

Alternativas recusadas: prazo cego maior (só adia), limite de segurança de 30 dias para
não marcados (um Vídeo preso por 30 dias perderia o original) e varredura no `videos` com
coluna nova para prazo exato (custo sem ganho para o Dono).

## Critérios de aceite

- [ ] Vídeo não-terminal com original mais velho que o prazo continua com o original.
      Provar contra MinIO real, com prazo curto ou com inspeção da regra aplicada.
- [ ] Vídeo terminal tem o original marcado, pelos três caminhos que chegam a terminal.
- [ ] `INSERT` que falha depois do upload não deixa objeto, quando a limpeza funciona.
- [ ] Marcação que falha não reverte nem bloqueia a transição de estado.
- [ ] Objetos já existentes: decidir e documentar o que acontece com eles no deploy.
- [ ] **ADR 0005** sobre a retenção do original, emenda no mapa e linha em "Decisões até aqui".
- [ ] `./mvnw test` e `scripts/smoke.sh` verdes.
