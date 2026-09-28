# O original só expira depois do desfecho

O arquivo que o Dono enviou fica no bucket `videos` do MinIO, e até aqui expirava 7 dias depois
do envio, qualquer que fosse o estado do Vídeo. Um Vídeo parado em DLQ, no Estacionamento ou num
backlog de mais de uma semana perdia o original, e nenhum resgate o recuperava: é *Vídeo perdido*
na terceira forma do [`CONTEXT.md`](../../CONTEXT.md). Decidimos que **o original só pode expirar
depois de o Vídeo chegar a `CONCLUIDO` ou `FALHOU`**. O `videos` grava a tag `desfecho=sim` no
objeto quando a transição terminal acontece, e a regra de ciclo de vida do bucket `videos` expira
só o que tem essa tag. Original sem a tag nunca expira.

Decidido no [ticket 105](../wayfinder/tickets/105-original-so-expira-depois-do-desfecho.md), que
reverte em parte a retenção do [ticket 011](../wayfinder/tickets/011-limites-operacionais.md):
ciclo de vida do MinIO, 7 dias nos dois buckets, "zero código". O bucket `pacotes` continua como
o 011 decidiu.

## Considered Options

**Prazo cego maior**, 30 ou 90 dias sem filtro, foi recusado porque só adia a perda. Um Vídeo
preso por mais tempo que o prazo perde o original do mesmo jeito.

**Tag mais um limite de segurança para o que não tem tag**, por exemplo 30 dias, foi recusado
pelo mesmo motivo. É o limite de segurança que um Vídeo preso alcança. Sem ele, "só depois do
desfecho" vale de verdade.

**Varredura no `videos` com coluna de prazo exato**, apagando N dias depois do desfecho em vez
de N dias depois do envio, foi recusada pelo custo: coluna nova, `@Scheduled` novo e código de
exclusão no caminho de estado. O ganho seria só a precisão da janela, e o Dono não baixa o
original, só o Pacote.

## Consequences

- **A janela depois do desfecho varia de 0 a 7 dias.** A regra do MinIO conta a partir da
  **criação** do objeto, não da tag. Um Vídeo que levou 10 dias para ter desfecho perde o
  original no primeiro ciclo do scanner depois de ganhar a tag. Isso é aceito: depois do
  desfecho ninguém lê o original.
- **A marca não reverte nem segura a transição.** Ela vem depois do `UPDATE` terminal, e a falha
  dela é engolida no `core` (`TransicaoDeVideo.marcarDesfechoDoOriginal`) e registrada em log no
  `ArquivoMinioAdapter`. Se a falha subisse, a entrega voltaria para a fila, a reentrega
  encontraria a linha já terminal e não marcaria de novo. No `FALHOU` a marca vem depois do aviso
  ao Dono, para que as repetições contra um MinIO fora não atrasem o e-mail, e vem mesmo quando o
  aviso falha. A marca é gravada mesmo quando o `UPDATE` altera zero linhas, porque isso
  só acontece com linha que já é terminal. A tag é idempotente.
- **O envio que falha no `INSERT` apaga o original, mas só depois de confirmar que a linha não
  existe.** A falha do `INSERT` pode ser ambígua: a conexão pode cair depois de o `COMMIT` chegar
  ao servidor, e a repetição do `RepeticaoNoPostgres` então recebe violação de chave. Nesse caso a
  linha commitada já é um Vídeo aceito, e a varredura do
  [ADR 0003](0003-reconciliacao-por-varredura.md) vai publicar o comando dele. Apagar o original
  sem olhar seria perder o Vídeo. Por isso `EnviarVideoUseCase` busca a linha antes. Se a busca
  também falhar, por exemplo com o Postgres fora, o original fica.
- **Vazamento residual, documentado e aceito.** Três casos deixam um original sem tag para sempre:
  crash entre o `UPDATE` terminal e a marca, marca que falha depois das repetições, e limpeza do
  órfão que falha ou não confirma a ausência da linha. Nos três sobra objeto, nenhum Vídeo se
  perde. O log `Original sem a marca do desfecho` / `Original sem linha nao foi apagado`, com a
  chave, é o rastro para limpeza manual.
- **Objetos que já existiam no deploy não têm tag e passam a não expirar.** O seed troca a
  configuração inteira do bucket (`mc ilm import`), então a regra antiga sem filtro some no
  primeiro boot. Os originais de Vídeos ainda sem desfecho ficam protegidos, que é o objetivo. Os
  de Vídeos que já eram terminais viram vazamento. Não há backfill: numa demo com volume local o
  volume é pequeno, e um backfill precisaria cruzar Postgres e MinIO para marcar só os terminais.
  Quem quiser limpar pode listar `select chave_video from video where estado in ('CONCLUIDO',
  'FALHOU')` e marcar cada chave com `mc tag set local/videos/<chave> desfecho=sim`.
- **O seed deixou de acumular regra.** `mc ilm rule add` sem ID acrescentava uma regra nova a
  cada execução do seed (confirmado contra `minio/mc:RELEASE.2025-08-13T08-35-41Z`: duas
  execuções, duas regras iguais por bucket; o volume do Compose deste repositório tinha **46**
  por bucket antes do seed novo, e uma depois). O `import` substitui, então o bucket `pacotes` também
  passou a usar ele. A retenção do `pacotes` é a mesma, 7 dias sem filtro.
- **A tag é um contrato que o build não liga.** `ArquivoMinioAdapter` (`TAG_DO_DESFECHO`),
  `docker/minio/seed.sh` e o literal do `OriginalSoExpiraDepoisDoDesfechoTest` precisam dizer o
  mesmo par. O passo 13 do `scripts/smoke.sh` lê a tag da regra aplicada e reprova se o original de algum dos dois
  terminais do smoke não tiver exatamente essa tag. O LocalStack dos Dev Services guarda a regra,
  mas não a executa, por isso o `@QuarkusTest` prova só a marcação
  (`OriginalSoExpiraDepoisDoDesfechoTest`).
- **A expiração em si não foi observada.** O MinIO não permite adiantar o relógio do scanner, e
  o menor prazo da regra é 1 dia. O filtro por tag foi confirmado no MinIO fixado
  (`RELEASE.2025-09-07T16-13-09Z-cpuv1`): a regra é aceita, exportada e listada com a tag. Que o
  scanner respeite o filtro é comportamento documentado do S3, não medido aqui.
