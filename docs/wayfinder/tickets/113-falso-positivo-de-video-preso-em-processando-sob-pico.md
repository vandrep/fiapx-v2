# O falso positivo de Vídeo preso em `PROCESSANDO` sob pico

- id: 113
- label: ready-for-agent
- status: aberto
- assignee: claude (sessão de 2026-09-14, SHA inicial f40b703)
- bloqueado-por:
- prioridade: P3

## Origem

Limite deixado pelo [ticket 106](106-deteccao-de-video-preso-pelo-estado.md), em 2026-09-14,
sobre `develop @ 3da5e67`. A revisão do 106 apontou o limite, e ele ficou registrado sem
medição. O mantenedor aprovou abrir este ticket.

## O problema

O alerta de Vídeo preso em `PROCESSANDO` conta 30 min desde o `iniciada_em`, que é o instante da
**primeira** tentativa. A derivação do número supõe três tentativas **seguidas**: 3 × 420 s mais o
aviso da falha dão ~21,5 min.

Nada garante que as tentativas saiam seguidas. Quando uma réplica do `extracao` morre com a
Extração em voo (SIGKILL, OOM), a entrega volta para `extracao.extrair`. Se a fila quorum a
recoloca no **fim**, a próxima tentativa espera todo o backlog. Num pico longo, um Vídeo legítimo
passaria dos 30 min ainda em `PROCESSANDO`, e o alerta chamaria um humano à toa.

O 106 não mediu três coisas:

- se o RabbitMQ 4.3 recoloca essa entrega no começo ou no fim da fila quorum;
- quanto tempo ela espera;
- se isso passa dos 30 min em algum regime realista deste sistema.

Os 420 s por tentativa também não são teto duro, porque download e upload não têm timeout.

## O que medir

Rajada contra o Compose com uma réplica do `extracao` derrubada durante a drenagem. O modo
`mata-extracao` do `scripts/carga/conservacao.sh` já faz isso. Fixe os critérios antes de rodar,
como nos tickets 025–028. Para cada Vídeo cuja tentativa foi interrompida, registre:

- a posição em que a entrega voltou à fila;
- o intervalo entre o `iniciada_em` e o desfecho;
- o maior valor do gauge `fiapx_videos_presos{estado="PROCESSANDO"}` durante a corrida. Se a
  stack de observabilidade estiver desligada pelo overlay de carga, use a mesma contagem feita
  direto no Postgres.

A corrida passa de 10 min: rode sob `systemd-inhibit`.

## Desfechos possíveis

- **O limite não se realiza** (entrega volta ao começo, ou a espera cabe na folga): registre a
  medição e corrija o texto do limite no 106, no `alertas.yaml` e ao lado do número.
- **O limite se realiza**: proponha a mudança de critério ao mantenedor antes de implementar. Uma
  opção é o `PROCESSANDO` também esperar a fila drenar, como já faz o `RECEBIDO`. Outra é contar
  desde a tentativa corrente, e não desde a primeira.

## Critérios de aceite

- [ ] Critérios fixados por escrito antes da corrida.
- [ ] Posição da entrega recolocada e intervalo `iniciada_em` → desfecho medidos sob
      `mata-extracao`, com o volume da rajada registrado.
- [ ] Um dos dois desfechos acima, com o texto dos limites atualizado onde o 106 os escreveu.
- [ ] Linha em "Decisões até aqui" no mapa.

## Critérios, fixados antes de rodar

Escritos em 2026-09-14, sobre `develop @ f40b703`, com o instrumento abaixo e antes de qualquer
corrida. Imagem do `extracao` reconstruída a partir desse commit, porque a `latest` local era
anterior ao 102; a anterior ficou como `ghcr.io/vandrep/fiapx-extracao:antes-113`. Postgres com
zero Vídeos em `PROCESSANDO` e scratch vazio antes da primeira rodada.

### Instrumento

O modo `mata-extracao` do `conservacao.sh` ganhou medição, sem julgamento novo:

- **Quem foi interrompido.** O scratch do `extracao` é um volume só, com uma entrada
  `{idVideo}-{sufixo}` por tentativa. A tentativa que termina limpa a sua; a morta pelo `SIGKILL`
  deixa o órfão, e o boot só apaga órfão com mais de 60 min. A diferença entre a listagem antes da
  rajada e a de depois da drenagem é o conjunto de tentativas interrompidas, sem corrida com o kill.
- **Fila no kill.** `messages_ready` e `messages_unacknowledged` de `extracao.extrair`, lidas
  imediatamente antes do `docker kill`, e o instante do kill.
- **Posição.** Número de Vídeos da rodada cuja primeira tentativa começou depois do kill e antes
  do desfecho do interrompido (`oraculo.sh interrompidas`).
- **Intervalo.** `finalizado_em − iniciada_em` do interrompido.
- **Presos.** A cada amostra da drenagem (5 s), o predicado do gauge
  `fiapx_videos_presos{estado="PROCESSANDO"}` direto no Postgres, com a idade do `PROCESSANDO`
  mais velho (`oraculo.sh presos`). O overlay de carga desliga a observabilidade.

### Rodadas

Duas, as duas `mata-extracao` com 4 réplicas, e as duas sob
`systemd-inhibit --what=sleep:idle`:

| Rodada | Pergunta | Comando |
|---|---|---|
| A | Onde a entrega volta | `FIAPX_ROTULO=113-a-controle scripts/carga/conservacao.sh mata-extracao 400` |
| B | Se passa de 30 min num pico realista | `FIAPX_FIXTURE=carga-2min.mp4 FIAPX_SEGUNDOS_POR_VIDEO=21 FIAPX_VUS=200 FIAPX_ROTULO=113-b-carga2min scripts/carga/conservacao.sh mata-extracao 400` |

A rodada A usa o fixture de controle: a espera é curta, mas começo e fim da fila ficam ~400
posições um do outro. A rodada B é o regime realista fixado aqui: a rajada padrão do harness,
400 envios, com o fixture de 2 min de 720p, a ~21 s por Vídeo numa réplica (ticket 026). A
drenagem esperada é ~35 min, então um Vídeo interrompido no começo da drenagem e recolocado no fim
esperaria mais que o limiar. `FIAPX_VUS=200` porque 400 conexões saturaram o instrumento no 108.

### Validade de cada rodada

- Ao menos uma tentativa interrompida (portão 0 do script). Sem ela, a rodada é repetida.
- Nenhum intervalo maior que 60 s entre amostras consecutivas da drenagem. Um intervalo assim é o
  host ausente, e a rodada é descartada.
- Os critérios 1 a 5 do `conservacao.sh` são reportados como saem. Reprovação neles é achado
  próprio, e não invalida a medição de posição.

### Leitura

- **Posição no começo:** posição ≤ 4 (as réplicas). **No fim:** posição ≥ 90% das mensagens
  prontas no kill. Entre os dois, registra-se o número, sem classificar.
- **O limite se realiza** se, na rodada B, algum Vídeo interrompido tiver intervalo `iniciada_em` →
  desfecho acima de 1800 s, ou se a contagem de presos passar de zero em alguma amostra.
- **O limite não se realiza** se a rodada B terminar com todos os intervalos ≤ 1800 s e a
  contagem sempre zero, **e** a posição da rodada A for no começo. Posição no fim com a rodada B
  abaixo do limiar não fecha o ticket por esse desfecho: o número depende do tamanho do pico, e a
  conta da espera vai para o mantenedor junto com a medição.
