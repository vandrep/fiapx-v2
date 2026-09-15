# A posição da entrega devolvida por `nack` com requeue sob pico

- id: 119
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Critérios fixados por escrito antes da corrida.
- [ ] Posição da entrega devolvida por `nack` e intervalo `iniciada_em` → desfecho medidos, com o
      volume da rajada e as mensagens prontas no `nack` registrados.
- [ ] Evidência de que a injeção não esgotou entregas de Vídeos bons: zero `FALHOU` e o
      `redeliver` da fila antes e depois.
- [ ] Um dos dois desfechos acima, com o texto dos limites atualizado.
- [ ] Linha em "Decisões até aqui" no mapa.
