# O Vídeo é aceito no commit da linha, não no publish

- id: 104
- label: ready-for-agent
- status: aberto
- assignee: claude
- bloqueado-por:
- prioridade: P1

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)).

## O problema

`EnviarVideoUseCase` encadeia objeto no MinIO → `INSERT` com marca nula → publish de
`ExtrairVideo` → marca. Se o publish falhar, por exemplo com o broker bloqueado por alarme de
disco ou memória, a exceção sobe e o `POST /videos` responde `500`. Nesse ponto o objeto e a
linha `RECEBIDO` já existem, e a reconciliação do
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) republica o comando: o Vídeo é
processado e aparece na listagem, enquanto o cliente acredita que falhou e pode reenviar. Isso
é leitura do código, não medição.

## Decisão

**O aceite é o commit da linha.** Depois do `INSERT`, a garantia do ADR 0003 já cobre o
comando, então a falha do publish não falha a requisição: o `POST` responde `202`, com a marca
nula, e a varredura publica depois. O publish na borda ganha um **teto de tempo**, para que um
broker bloqueado não segure a requisição; valor a decidir por medição, coerente com as esperas
do ADR 0001.

Alternativas recusadas: desfazer linha e objeto e responder `503` (compensação que também pode
falhar) e manter o `500` ambíguo.

## Critérios de aceite

- [ ] Teste do use case: publish que falha depois do `INSERT` resulta em `202` com Vídeo
      `RECEBIDO` e marca nula; a reconciliação o publica depois.
- [ ] Teste com publish que não responde: a requisição termina dentro do teto e responde `202`.
- [ ] Falha **antes** do commit (MinIO ou `INSERT`) continua sem `202`.
- [ ] Contrato em `docs/contratos/http-videos.md` diz o que o `202` promete.
- [ ] Emenda no ADR 0003 e linha em "Decisões até aqui" no mapa.
- [ ] `./mvnw test` verde a partir da raiz.
