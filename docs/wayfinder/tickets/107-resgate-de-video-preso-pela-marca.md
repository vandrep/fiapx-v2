# Resgate de Vídeo preso pela marca de publicação

- id: 107
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial 1ce43a9)
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

- [x] Script em `scripts/` com recusa explícita para Vídeo com desfecho.
- [x] Teste do adapter contra Postgres: `PROCESSANDO` sem marca é pendente, `PROCESSANDO` com
      marca não é.
- [x] Prova de ponta a ponta no Compose: Vídeo forçado a preso, resgatado, chega a terminal.
- [x] Runbook curto (onde o alerta do 106 aponta), incluindo a purga da mensagem residual.
- [x] Emenda no ADR 0003 sobre o novo predicado e linha em "Decisões até aqui" no mapa.

## Resolução

Implementado em 2026-09-14 sobre `develop @ 1ce43a9`.

**Predicado.** `buscarComandosPendentes` passou a `estado in (RECEBIDO, PROCESSANDO)`, com a mesma
folga e a mesma ordenação. O dublê em memória acompanha. O índice `ix_video_comando_pendente` não tem
estado no predicado, então o `init.sql` não mudou.

**Script.** `scripts/resgata-video.sh <idVideo>` faz um só comando SQL: o `UPDATE` repete o predicado
do estado, então um Vídeo que chega a desfecho no meio não é tocado. Saídas: `0` resgatado, `2`
recusado por desfecho, `3` não encontrado, `1` uso ou banco.

**Runbook.** `docs/operacao/resgate-de-video-preso.md`: achar o Vídeo, resgatar e purgar a mensagem
residual. As anotações dos alertas 1, 4 e 5 de `alertas.yaml` apontam para ele. Duas coisas que o
ticket não dizia e o runbook diz: o alerta de `PROCESSANDO` continua aceso durante a nova série,
porque `iniciada_em` não é reescrito; e ler o Estacionamento com requeue gasta entrega da quorum
queue, que não declara limite e fica com o padrão do RabbitMQ.

**Documentação.** Emenda no ADR 0003, linha no mapa e parágrafo do script de prova no `AGENTS.md`.

### Leitura do `FALHOU`

A decisão diz "`FALHOU` sem aviso: zera `falha_publicada_em`" e "`FALHOU` com aviso já publicado:
recusa". O único aviso que o `videos` observa é a própria `falha_publicada_em`. Então o `FALHOU` sem
aviso já tem a marca nula, já é pendente da varredura, e não há o que zerar: o script só o reporta,
com saída `0`. O efeito prático é que o resgate não alcança o `VideoFalhou` parado em
`notificacao.dlq`, porque aquele Vídeo tem marca e é recusado. Se a intenção era outra, ela precisa
de ticket novo.

A leitura "todo `PROCESSANDO` tem marca" também não é exata: um crash entre o publish e a marca,
seguido do início da Extração, deixa um `PROCESSANDO` sem marca. A varredura agora o republica, e a
Extração roda duas vezes. É a duplicata que o ADR 0003 já aceitava, e a emenda a registra.

### Validação

- `VideoDataSourceAdapterTest.processandoSemMarcaEPendenteEComMarcaNao`, contra o Postgres dos Dev
  Services. Reprovou antes da mudança no predicado ("PROCESSANDO sem marca tem de ser republicado")
  e passou depois. Cobre também o terminal sem marca, que fica de fora.
- `scripts/resgate-ponta-a-ponta.sh` verde no Compose, com a imagem do `videos` construída
  localmente:
  - dois Vídeos com marca e comando purgado, um posto em `PROCESSANDO` por SQL;
  - passados 100 s do envio, folga mais uma varredura, os dois seguiam presos e a fila vazia;
  - resgatados, os dois chegaram a `CONCLUIDO` com a marca regravada;
  - recusas conferidas: `CONCLUIDO`, `FALHOU` com aviso, id inexistente e id inválido.
- A primeira corrida da prova reprovou no passo 4 e achou dois defeitos do script: tipos
  divergentes no `UNION` e o código `3` do `psql` em erro de SQL, que colidia com "não encontrado".
  Os dois Vídeos daquela corrida foram resgatados à mão com o script corrigido e chegaram a
  `CONCLUIDO` em ~15 s.
- `./mvnw test` na raiz: 502 testes verdes (183 `videos`, 290 `extracao`, 29 `notificacao`).

### Revisão

`/code-review` em dois eixos, sem violação dura de padrão. Corrigido depois dela, e o script
reconferido contra o Postgres:

- A leitura de fallback do script usava o snapshot do início do comando. Um Vídeo que virasse
  terminal durante o `UPDATE` saía como "marca apagada", sem nada escrito. Agora só a linha
  devolvida pelo `UPDATE` conta como resgate.
- A ressalva da emenda também cobre o confirm que falha depois de o broker aceitar.
- O alerta 3 aponta para o runbook quando a fila é de fim de linha.
- O dublê usa o mesmo conjunto de estados do adapter.

Ficou em aberto: a leitura do `FALHOU`, acima, e a prova não exercita uma mensagem residual de
verdade numa DLQ.
