# Resgate de Vídeo preso pela marca de publicação

- id: 107
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por: 106
- prioridade: P2

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido*. O termo **resgate** foi registrado no [`CONTEXT.md`](../../../CONTEXT.md).

## O problema

Hoje, o que tira um Vídeo do fim da linha (`videos.dlq`, `notificacao.dlq`, Estacionamento) é
um humano no management UI, mexendo em mensagens. Isso não resgata o que foi descartado, e não
há procedimento escrito.

## Decisão

**Resgate = apagar a marca.** Um script recebe o id do Vídeo:

- Vídeo não-terminal: zera `comando_publicado_em`. A varredura do
  [ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) republica `ExtrairVideo` pelo mesmo
  caminho do envio.
- `FALHOU` sem aviso: zera `falha_publicada_em`, e a varredura republica `VideoFalhou`.
- Vídeo `CONCLUIDO`, ou `FALHOU` com aviso já publicado: recusa. Terminal é terminal.

`buscarComandosPendentes` (`VideoDataSourceAdapter`) passa a aceitar `RECEBIDO` **ou**
`PROCESSANDO`. É seguro: todo `PROCESSANDO` tem marca, então só o resgate o traz de volta.
O comando republicado é mensagem nova e começa uma nova série de tentativas; aceito, por ser
ação humana. A mensagem que ficou na DLQ ou no Estacionamento é purgada depois do resgate,
porque vira duplicata inofensiva pela idempotência das transições.

Recusado: mover mensagens entre filas por shovel ou UI (não resgata o descartado) e endpoint
administrativo (exigiria papel de administrador no Keycloak por pouca coisa).

## Critérios de aceite

- [ ] Script em `scripts/` com recusa explícita para Vídeo com desfecho.
- [ ] Teste do adapter contra Postgres: `PROCESSANDO` sem marca é pendente, `PROCESSANDO` com
      marca não é.
- [ ] Prova de ponta a ponta no Compose: Vídeo forçado a preso, resgatado, chega a terminal.
- [ ] Runbook curto (onde o alerta do 106 aponta), incluindo a purga da mensagem residual.
- [ ] Emenda no ADR 0003 sobre o novo predicado e linha em "Decisões até aqui" no mapa.
