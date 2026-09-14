# O falso positivo de Vídeo preso em `PROCESSANDO` sob pico

- id: 113
- label: ready-for-agent
- status: aberto
- assignee:
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
