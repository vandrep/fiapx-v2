# A garantia de estacionamento do 029 nunca foi confirmada sob carga

- id: 075
- label: wayfinder:bug
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 029](029-terminal-na-dlq-do-extracao.md) está `fechado`, e a própria `## Resolução`
dele diz que a garantia **não** foi provada:

> A segunda parte, o modo `mata-publicacao` do `conservacao.sh`, só conseguiu subir depois do
> 038, e chegou ao veredito com o critério do estacionamento **reprovado** por prazo: zero
> mensagens novas em 241 s, limite de 240 s. A garantia deste ticket, portanto, está construída
> e provada em teste, mas **não confirmada sob carga**.

Ela aponta para o [038](038-override-de-canal-por-variavel-quebra-o-boot.md) como quem carrega
o diagnóstico pendente. O 038 também está `fechado`, e a sua resolução devolve a pendência sem
dono:

> O boot está corrigido; a garantia de estacionamento do 029 **não foi confirmada** no prazo do
> harness e permanece pendente de diagnóstico. A medição não distingue atraso, circulação ou
> perda; não se atribui causa sem investigar.

Dois tickets fechados apontando um para o outro fecham o assunto sem que ninguém o carregue. O
que está em jogo não é registro: é o fundo de toda a recuperação do sistema — o caminho que
transforma o `x-delivery-limit=3` esgotado em `ExtracaoFalhou` e impede o Vídeo de ficar em
`PROCESSANDO` para sempre ([ADR 0001](../../adr/0001-politica-de-falhas.md)).

O [073](073-agents-md-descreve-conservacao-de-um-estado-que-passou.md) remediu `limpo` e
`mata-videos` contra o código atual, e os dois passaram. `mata-publicacao` ficou de fora, então
a única medição que existe dele é a de antes dos tickets 061 a 069.

## O que entregar

- Uma rodada de `scripts/carga/conservacao.sh mata-publicacao` contra o **HEAD atual**, com as
  imagens reconstruídas antes de medir. O 073 registrou o gotcha que invalida a rodada: o
  harness mede a imagem que estiver por perto, e imagens de um dia atrás passam despercebidas.
- O veredito com os três critérios julgados, e o número do critério do estacionamento.
- Se aprovar: a `## Resolução` do 029 ganha a confirmação que faltava, e este ticket registra a
  medição.
- Se reprovar: a causa nomeada — atraso, circulação ou perda —, e o 029 **reabre**, porque a
  garantia que ele declara não existe. Não se atribui causa sem investigar; o diagnóstico é
  parte do trabalho, não a rodada sozinha.

## Como medir

Corrida longa: use `systemd-inhibit --what=sleep:idle`, como o 073 fez. O host suspende no meio
de uma medição de mais de dez minutos e corrompe o denominador em silêncio.

## Critérios de aceite

- [x] Rodada de `mata-publicacao` contra imagens construídas do HEAD, com o log guardado
- [x] Os três critérios julgados, com o número do estacionamento registrado neste ticket
- [x] O 029 confirmado ou reaberto, conforme o veredito — não fechado com aceite reprovado
- [x] Se reprovou, a causa nomeada entre atraso, circulação e perda

## Resolução

**Reprovou, de novo, e desta vez com causa.** HEAD no início da rodada:
`7f45ac74b37048cef893582b54d35871abd134bb`. Nenhum arquivo de `videos/src`, `extracao/src`,
`notificacao/src` ou `pom.xml` mudou desde a construção das imagens `:latest` do 073
(`406392d`), mas as imagens foram reconstruídas do zero mesmo assim — `./mvnw package` na raiz
seguido de `docker build` nos três serviços — para não repetir por suposição o gotcha que o
próprio 073 registrou. O `docker build` reproduziu os mesmos digests, confirmando que as
imagens já estavam corretas.

Rodada sob `systemd-inhibit --what=sleep:idle`:
`COMPOSE_PROJECT_NAME=fiapx-v2 FIAPX_ROTULO=075-mata-publicacao scripts/carga/conservacao.sh mata-publicacao`,
3 envios (default do modo), log completo em `scripts/carga/saida/075-mata-publicacao/`.

Veredito:

1. **Aprovado** — zero recusas em 3 envios.
2. **Aprovado** — 3/3 vídeos presos em `PROCESSANDO`, zero `CONCLUIDO`, zero `FALHOU`, zero
   `AUSENTE`.
3. **Reprovado** — 0/3 mensagens novas em `extracao.extrair.estacionamento` em 241s, limite de
   240s. Mesmo número do 038, no mesmo critério.

### Diagnóstico

A rodada não parou no limite de 240s: o stack ficou de pé mais 10 minutos depois do veredito,
para investigar. Nesse tempo, `extracao.extrair.dlq` e `extracao.extrair.estacionamento`
continuaram em zero, e `extracao.extrair` seguiu devolvendo `WARN SRMSG17013` a cada ~30s em
cada uma das 4 réplicas — o retry-on-fail-attempts=6/interval=5s do publicador
(`extracao-falhou`) se esgotando a cada vez, sem nunca escalar.

A causa está no próprio `ProcessarExtracaoUseCase` (`extracao/src/main/java/br/com/fiapx/
extracao/core/usecases/extracao/ProcessarExtracaoUseCase.java:82-88`). Para uma
`FalhaPermanenteDeExtracaoException` — o caso do `invalido.mp4` deste modo —, `tratarFalha`
devolve **diretamente** o `CompletableFuture` de `extracaoEventosSender.enviarFalhou(...)`. Se
essa publicação falhar (o defeito que este modo injeta de propósito), o futuro falho sobe pela
cadeia inteira e `ExtrairVideoConsumer` — cujo Javadoc documenta a regra "completa normalmente
= ack; completa excepcionalmente = nack com requeue" — trata uma falha **permanente e já
classificada** como se fosse transitória: nack com requeue no canal `extrair-video`, mandando
o ffprobe rodar de novo do zero sobre o mesmo arquivo inválido.

Isso por si só reproduziria um loop limitado pelo `x-delivery-limit=3` da fila `extracao.
extrair` — o mecanismo que o 029 conta para eventualmente escalar ao DLQ e daí ao
estacionamento. Só que o limite não dispara: uma inspeção dos headers da mensagem em voo
(`rabbitmqadmin get ... --ack-mode ack_requeue_true`, com as réplicas do `extracao`
paradas para a mensagem ficar `ready`) mostrou

```
x-acquired-count: 24  x-delivery-count: 1
x-acquired-count: 25  x-delivery-count: 2
x-acquired-count: 24  x-delivery-count: 1
```

— 24-25 aquisições reais (uma a cada ~30s, batendo com os `WARN` no log) contra um
`x-delivery-count` de 1-2, muito abaixo do `x-delivery-limit=3` configurado
(confirmado via API de management: o argumento vivo da fila é `x-delivery-limit: 3`). O
contador que a fila quorum usa para decidir quando esgotar não está subindo no mesmo ritmo das
tentativas reais, então o limite nunca é cruzado e a mensagem nunca sai de `extracao.extrair`.

**Causa: circulação.** Não é atraso (nada indica que terminaria, dado mais tempo — o defeito
injetado é permanente) nem perda silenciosa (a mensagem nunca desaparece, seguiu presente e
sendo reentregue por mais de 15 minutos de observação). É um loop sem fundo entre a reentrega
de `extrair-video` e a nova tentativa de publicar `ExtracaoFalhou`, que nunca escala ao
mecanismo de estacionamento que o 029 desenhou — o mesmo loop sem fundo que o
`failure-strategy=reject` foi desenhado para evitar, só que se forma um passo antes de chegar
onde esse desenho age.

Evidência completa em `scripts/carga/saida/075-mata-publicacao/`: log da rodada
(`conservacao.log`, capturado em `/tmp/075-mata-publicacao.log`), log das 4 réplicas do
`extracao` (`extracao-<container>.log`), o dump de headers
(`headers-x-delivery-count.txt`) e o estado final das três filas envolvidas
(`estado-final-*.json`).

**O 029 reabre** com este diagnóstico anexado — a garantia que ele declara ("falha definitiva
para no estacionamento") não existe para falhas permanentes cuja própria publicação de falha
falha. Corrigir é fora de escopo deste ticket, que era medir e diagnosticar, não construir; o
029 reaberto é quem carrega a correção.
