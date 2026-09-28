# O original só expira depois do desfecho

- id: 105
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial 8fb8655)
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

- [x] Vídeo não-terminal com original mais velho que o prazo continua com o original.
      Provar contra MinIO real, com prazo curto ou com inspeção da regra aplicada.
- [x] Vídeo terminal tem o original marcado, pelos três caminhos que chegam a terminal.
- [x] `INSERT` que falha depois do upload não deixa objeto, quando a limpeza funciona.
- [x] Marcação que falha não reverte nem bloqueia a transição de estado.
- [x] Objetos já existentes: decidir e documentar o que acontece com eles no deploy.
- [x] **ADR 0005** sobre a retenção do original, emenda no mapa e linha em "Decisões até aqui".
- [x] `./mvnw test` e `scripts/smoke.sh` verdes.

## Resolução

Implementado em 2026-09-14 sobre `develop @ 8fb8655`. Registrado no
[ADR 0005](../../adr/0005-retencao-do-original.md).

**Marca.** `ArquivoGateway` ganhou `marcarDesfechoDoOriginal` e `apagarOriginal`. O
`ArquivoMinioAdapter` grava a tag `desfecho=sim` por `PutObjectTagging`, com a repetição do
ADR 0001. Se a falha persistir, ele registra um `warn` com a chave. O MinIO fixado aceita o filtro
por tag na regra de ciclo de vida, e isso foi conferido na sessão.

**Os três caminhos que chegam a terminal**, como a sessão os leu. Nenhum documento os nomeava, e
o `videos` só tem dois use cases que gravam terminal:

1. `ExtracaoConcluida` → `CONCLUIDO`.
2. `ExtracaoFalhou` → `FALHOU`. Cobre a falha permanente e as tentativas esgotadas, que chegam
   pelo mesmo evento.
3. A entrega que perde a corrida para o outro terminal (`UPDATE` com zero linhas). A linha já é
   terminal, então essa entrega marca também.

Os três têm teste de use case nos dois consumidores. Os dois primeiros também são provados contra
S3 real (`OriginalSoExpiraDepoisDoDesfechoTest`, LocalStack) e contra o MinIO do Compose (passo 13
do smoke).

**A marca nunca falha a entrega.** O `core` engole a falha em
`TransicaoDeVideo.marcarDesfechoDoOriginal`, inclusive a de um adapter que lance em vez de devolver
future falho. No `FALHOU` a marca vem **depois** do aviso, e vem mesmo quando o aviso falha. A
primeira versão marcava antes. A revisão apontou que um MinIO fora seguraria o e-mail pelas
repetições, e `oAvisoNaoEsperaAMarca` reprovou antes da troca.

**Desvio da decisão, deliberado:** a limpeza do órfão só apaga depois de `buscarPorId` confirmar
que a linha não existe. A falha do `INSERT` pode ser ambígua: a conexão cai depois do `COMMIT` e
a repetição do `RepeticaoNoPostgres` recebe violação de chave. Apagar nesse caso seria Vídeo
perdido, exatamente o que o ticket combate. O preço é que, com o Postgres fora de verdade, a busca
também falha e o órfão fica. Isso é vazamento, e a decisão já o aceita.

**Seed.** `mc ilm rule add` virou `mc ilm import` nos dois buckets. O `rule add` sem ID
**acumulava** uma regra por boot: o volume do Compose tinha 46 regras sem filtro em cada bucket,
e o seed novo deixou uma. A retenção do `pacotes` é a mesma (7 dias, sem filtro); só o mecanismo
mudou, para parar a acumulação.

**Objetos já existentes:** perdem a regra sem filtro no primeiro boot e não expiram mais. Não há
backfill; a receita manual está no ADR.

### Validação

- Testes de use case, todos novos:
  - `EnviarVideoUseCaseTest` (+5): órfão apagado, `INSERT` ambíguo que não apaga, busca que
    falha, limpeza que falha sem trocar a falha do envio, envio que não marca.
  - `ProcessarExtracaoConcluidaUseCaseTest` (+3) e `ProcessarExtracaoFalhouUseCaseTest` (+5):
    marca, marca que falha, corrida perdida, aviso antes da marca, aviso que falha e ainda marca.
- `OriginalSoExpiraDepoisDoDesfechoTest` (novo, `@QuarkusTest`, 4 testes): original sem marca
  antes do desfecho, marca pelos dois eventos reais no RabbitMQ, e `apagarOriginal` contra S3.
  Reprovou com o adapter ainda lançando `UnsupportedOperationException`.
- `./mvnw test` a partir da raiz, depois das correções da revisão: **487 testes verdes** (168
  `videos`, 290 `extracao`, 29 `notificacao`), `BUILD SUCCESS`.
- `scripts/smoke.sh` com a imagem do `videos` construída localmente: verde duas vezes, antes e
  depois da revisão. O passo 13 é novo: lê a regra aplicada aos dois buckets e confere que os
  originais do Vídeo `CONCLUIDO` e do `FALHOU` têm exatamente a tag da regra.

### Limites que ficam

- A **expiração** em si não foi observada. O menor prazo é 1 dia e o scanner não se adianta.
  Está provado que a regra foi aplicada com o filtro e que a marca é gravada.
- O original de um Vídeo não-terminal sem marca só é provado contra o LocalStack, não contra o
  MinIO do Compose. O smoke não consegue segurar um Vídeo em `RECEBIDO`.
- A tag é um literal em três lugares (adapter, seed e teste) que o build não amarra. Quem os amarra
  é o passo 13 do smoke.

