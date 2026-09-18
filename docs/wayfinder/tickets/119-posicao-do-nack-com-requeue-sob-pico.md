# A posição da entrega devolvida por `nack` com requeue sob pico

- id: 119
- label: ready-for-agent
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 45d18db)
- bloqueado-por:
- prioridade: P3

## Origem

Limite deixado pelo [ticket 113](113-falso-positivo-de-video-preso-em-processando-sob-pico.md),
em 2026-09-15, sobre `develop @ 0eaeeb8`. O mantenedor pediu que se abrisse este ticket.

## O problema

O 113 mediu que a entrega devolvida por **crash** de réplica volta ao começo de
`extracao.extrair`. Esse é o retorno pelo fechamento do canal. A falha transitória volta por outro
caminho, que o 113 não mediu. O `ExtrairVideoConsumer` dá `nack` com requeue
(`failure-strategy=requeue` → `basicNack(requeue=true)`). Isso acontece quando a Extração termina
em `FalhaTransitoriaDeExtracaoException`: o MinIO segue fora depois das repetições do
`comRepeticao`, falha o scratch, ou o ffmpeg sai com um exit code sem motivo permanente.

O limiar de 30 min do alerta de `PROCESSANDO` supõe três tentativas seguidas (ver a derivação no
`application.properties` do `videos`). Se a fila quorum puser a entrega do `nack` no **fim**, a
próxima tentativa espera o backlog inteiro. Num pico, um Vídeo legítimo passaria do limiar, e o
alerta chamaria um humano à toa. Duas falhas transitórias no mesmo Vídeo dobram a espera.

O texto do limite hoje diz que essa posição não foi medida: regra 4 do `alertas.yaml`,
comentário ao lado do limiar e seção "Limites que ficam" do 113.

## O que medir

Rajada contra o Compose com falha transitória injetada durante a drenagem. Fixe os critérios por
escrito antes de rodar, como no 113. Para cada Vídeo cuja tentativa terminou em `nack`, registre:

- a posição em que a entrega voltou à fila, contra as mensagens prontas no instante do `nack`;
- o intervalo entre o `iniciada_em` e o desfecho;
- o maior valor da contagem de presos em `PROCESSANDO` (`scripts/carga/oraculo.sh presos`).

Duas coisas que o 113 não precisou resolver e que este precisa:

- **Identificar a tentativa que deu `nack`.** O órfão no scratch não serve aqui: a falha transitória
  limpa o próprio scratch (`ProcessarExtracaoUseCase`). Uma opção é partir dos Vídeos em
  `PROCESSANDO` no instante da injeção, que são as Extrações em voo. Outra é o `redeliver` de
  `message_stats` da fila, que dá a contagem mas não o id.
- **Injetar sem gastar as três entregas.** Um MinIO fora por tempo demais faz toda réplica pegar a
  próxima mensagem e falhar também. Isso esgota o `x-delivery-limit` e manda Vídeos bons para a
  DLQ, que é outro fenômeno. A janela da falha precisa ser curta o bastante para derrubar só as
  tentativas em voo, e o `redeliver` antes e depois diz se foi. Uma alternativa é `docker pause`
  no `minio` com duração calibrada, que passe das repetições do `comRepeticao`.

Se a corrida passar de 10 min, rode sob `systemd-inhibit`. Com o fixture `carga-2min.mp4`, comece
com poucos VUs: com 200, o k6 ficou sem memória no 113.

## Desfechos possíveis

- **O limite não se realiza** (a entrega volta ao começo): registre a medição e corrija o texto do
  limite no `alertas.yaml`, ao lado do limiar e no 113, por `## Correção (119)`.
- **O limite se realiza**: proponha a mudança de critério ao mantenedor antes de implementar, com
  as mesmas opções do 113. Uma é o `PROCESSANDO` também esperar a fila drenar, como o `RECEBIDO`.
  Outra é contar desde a tentativa corrente.

## Critérios de aceite

- [x] Critérios fixados por escrito antes da corrida.
- [x] Posição da entrega devolvida por `nack` e intervalo `iniciada_em` → desfecho medidos, com o
      volume da rajada e as mensagens prontas no `nack` registrados.
- [x] Evidência de que a injeção não esgotou entregas de Vídeos bons: zero `FALHOU` e o
      `redeliver` da fila antes e depois.
- [x] Um dos dois desfechos acima, com o texto dos limites atualizado.
- [x] Linha em "Decisões até aqui" no mapa.

## Critérios, fixados antes de rodar

Escritos em 2026-09-15, sobre `develop @ 45d18db`; a versão consolidada abaixo foi fixada antes
da corrida final de 400 Vídeos registrada neste ticket. O instrumento será o modo `blip-minio` do `scripts/carga/conservacao.sh`, com a imagem que o
Compose estiver usando registrada no resultado. A stack deve começar sem mensagens pendentes,
sem `PROCESSANDO` da rodada e sem scratch de tentativa residual; Vídeos `CONCLUIDO` de rodadas
anteriores podem permanecer no banco porque o oráculo trabalha pela lista de ids nova.

### Instrumento

- **Rajada.** Publicar Vídeos válidos pela borda real, com backlog pronto em
  `extracao.extrair` e poucos VUs. O volume da rodada e as mensagens prontas/sem ack serão
  gravados antes da injeção.
- **Identificação.** Usar uma réplica do `extracao`, `max-outstanding-messages=1`, e esperar
  exatamente um Vídeo da rodada em `PROCESSANDO`. Esse id é a única Extração em voo no momento
  da falha; assim a subida de `message_stats.redeliver` pode ser atribuída a ele, sem usar o
  scratch — a falha transitória limpa o scratch.
- **Injeção.** Parar somente o container do `minio` com `docker stop --time 0` enquanto há um
  backlog e uma Extração em voo. Consultar a fila a cada 200 ms e reiniciar o MinIO assim que o
  primeiro incremento de `redeliver` aparecer. O teto da janela é 30 s; sem incremento, a rodada
  é inválida. Retomar no primeiro `redeliver`, em vez de manter a indisponibilidade por tempo
  fixo, é o que impede consumir a terceira entrega do mesmo Vídeo.
- **Posição.** No snapshot que observou o `redeliver`, registrar `messages_ready` e
  `messages_unacknowledged`. No Postgres, contar os Vídeos da rodada cuja primeira
  `iniciada_em` ficou entre a injeção e o instante observado; esse é o mesmo oráculo de posição
  do ticket 113, agora com uma única candidata identificada.
- **Desfecho.** Registrar `finalizado_em - iniciada_em` do Vídeo identificado e amostrar, a cada
  5 s, a contagem de presos em `PROCESSANDO` pelo predicado do gauge.
- **Conservação.** Capturar `redeliver` acumulado antes e depois, o delta, o censo final e as
  filas `extracao.extrair.dlq`/`extracao.extrair.estacionamento`. A injeção só vale se o delta de
  `redeliver` for menor que dois (no máximo uma reentrega, sem gastar a terceira entrega) e a
  rajada terminar com zero `FALHOU`.

### Validade e leitura

- A rodada é inválida sem backlog pronto, sem exatamente um Vídeo em voo ou sem incremento de
  `redeliver` no teto de 30 s; ela deve ser repetida, não interpretada como ausência de `nack`.
- O resultado é **começo** se a posição observada for menor ou igual a uma réplica; é **fim** se
  for pelo menos 90% das mensagens prontas antes da injeção. Entre os dois, o número é reportado
  sem classificação.
- O limite não se realiza se a posição for no começo, o Vídeo terminar em no máximo 1800 s desde
  `iniciada_em`, a contagem máxima de presos for zero, o delta de `redeliver` for menor que dois e
  todos os Vídeos válidos terminarem em `CONCLUIDO`.
- O limite se realiza se a entrega voltar ao fim, se o intervalo passar de 1800 s ou se algum
  Vídeo válido entrar na contagem de presos. Nesse caso, a medição não autoriza alterar o alerta:
  a mudança de critério volta ao mantenedor, como descrito acima.

## Resolução

Implementado em 2026-09-15 sobre `develop @ 45d18db`.

O modo `blip-minio` foi acrescentado ao `scripts/carga/conservacao.sh`. Ele verifica antes da
rajada que a fila principal, DLQ, estacionamento, qualquer Vídeo em `PROCESSANDO` e scratch estão vazios; usa uma
réplica do `extracao` para identificar o único Vídeo em voo, para o container do MinIO com
`docker stop --time 0`, observa a fila a cada 200 ms e religa o MinIO no primeiro incremento de
`redeliver`. A rodada é inválida se o contador chegar à segunda reentrega, pois a terceira entrega
já teria sido gasta. O `scripts/carga/oraculo.sh` ganhou as consultas `em-voo` e `nackadas`, que
registram a candidata, a posição e o intervalo; o harness grava as filas de DLQ e estacionamento
ao fim. O `trap` também religa o MinIO se a rodada abortar.

As imagens dos três serviços foram reconstruídas do código atual antes do ensaio:

| Serviço | Imagem | Digest local |
|---|---|---|
| `videos` | `ghcr.io/vandrep/fiapx-videos:latest` | `sha256:534c1295d0b72a6ac0d44ea944dc9d0e787f067dc590cd135dce982efc57478f` |
| `extracao` | `ghcr.io/vandrep/fiapx-extracao:latest` | `sha256:f487b93f859e911814a5d99647ab25887f1dd042e785bc6fdd5e1b6770493f9f` |
| `notificacao` | `ghcr.io/vandrep/fiapx-notificacao:latest` | `sha256:fe06c6f1404695bf03f634a8209490dc6317b3b115fbe62dcefdc002f52d4602` |

### Ensaio

Executado sob `systemd-inhibit --what=sleep:idle`:

```bash
COMPOSE_PROJECT_NAME=fiapx-v2 FIAPX_EXTRACAO_REPLICAS=1 FIAPX_VUS=100 \
FIAPX_ROTULO=119-pico-controle-2 systemd-inhibit --what=sleep:idle \
scripts/carga/conservacao.sh blip-minio 400
```

Resultado:

| Medida | Resultado |
|---|---|
| Volume / aceitos | 400 / 400 `202` |
| Pré-condições iniciais | fila, DLQ, estacionamento, `PROCESSANDO` e scratch vazios |
| Fila antes da injeção | 250 prontas, 1 sem ack |
| Fila no primeiro `nack` | 397 prontas, 1 sem ack |
| `redeliver` antes → depois | `0 → 1`, delta 1 |
| Posição da entrega devolvida | **0**, começo da fila |
| `iniciada_em` → desfecho | 15 s, `CONCLUIDO` |
| Maior contagem de presos | 0 |
| Censo final | 400 `CONCLUIDO`, 0 `FALHOU`, 0 presos |
| DLQ / Estacionamento | 0 / 0 mensagens |
| Drenagem | 161 s |

A posição foi calculada contando os Vídeos da rodada iniciados entre a injeção e o instante em
que o primeiro `redeliver` foi observado; nenhuma nova tentativa começou antes da candidata
voltar. A retomada imediata do MinIO consumiu uma só reentrega, e os Vídeos bons não chegaram à
DLQ nem ao Estacionamento.

O limite não se realiza para a falha transitória medida: a entrega voltou ao começo mesmo com 397
mensagens prontas, terminou dentro de 1800 s e não produziu presos. O texto foi atualizado no
`alertas.yaml`, no comentário do limiar em `videos/application.properties` e numa `## Correção
(119)` do ticket 113. A decisão entrou no mapa.

### Limites que ficam

- O relógio de 420 s não é teto duro, porque download e upload do MinIO não têm timeout próprio.
- A identificação foi isolada em uma réplica e uma tentativa em voo; não se extrapola o resultado
  para múltiplas falhas simultâneas ou para consumir repetidamente a mesma entrega.

### Validação

- `bash -n scripts/carga/conservacao.sh scripts/carga/oraculo.sh`: passou.
- `shellcheck scripts/carga/conservacao.sh scripts/carga/oraculo.sh`: sem novos avisos (os avisos
  remanescentes são os dois já existentes no script).
- `./mvnw package -DskipTests`: passou, incluindo as três cópias do teste arquitetural,
  `AckManual` e `entrypoint.sh`.
- `./mvnw test`: passou na raiz com Docker e Dev Services, incluindo os testes arquiteturais e
  os cenários BDD dos três serviços.
- O modo também grava `precondicoes-iniciais.txt` (incluindo a contagem bruta de `PROCESSANDO`) e
  `filas-finais.txt`; o guard-rail da terceira entrega é `redeliver_delta < 2`.
- Rodada de calibração final: 80/80 `CONCLUIDO`, pré-condições incluindo `PROCESSANDO` bruto
  vazias, `redeliver` 1→2, posição 0, intervalo 16 s, zero presos, DLQ/estacionamento vazios e
  amostra HTTP verde.
- Rodada de pico acima: todos os critérios do `conservacao.sh` verdes.
