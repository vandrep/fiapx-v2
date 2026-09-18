# O falso positivo de Vídeo preso em `PROCESSANDO` sob pico

- id: 113
- label: ready-for-agent
- status: fechado
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

- [x] Critérios fixados por escrito antes da corrida.
- [x] Posição da entrega recolocada e intervalo `iniciada_em` → desfecho medidos sob
      `mata-extracao`, com o volume da rajada registrado.
- [x] Um dos dois desfechos acima, com o texto dos limites atualizado onde o 106 os escreveu.
- [x] Linha em "Decisões até aqui" no mapa.

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

## Resolução

Medido em 2026-09-14 sobre `develop @ c6213fa`. **O limite não se realiza** para crash de réplica:
a entrega devolvida volta ao começo da fila quorum.

| Rodada | Volume | Prontas no kill | Posição | `iniciada_em` → desfecho | Maior contagem de presos | Conservação |
|---|---|---|---|---|---|---|
| A, `113-a-controle` | 400 × `controle-3s.mp4`, 400 VUs | 360 (4 sem ack) | **2** | 1 s | 0 | 400/400 `CONCLUIDO`, drenagem em 63 s |
| B2, `113-b2-carga2min-vus10` | 400 × `carga-2min.mp4`, 10 VUs | 352 (4 sem ack) | **3** | 30 s | 0 (`PROCESSANDO` mais velho: 25 s) | 400/400 `CONCLUIDO`, drenagem em 1449 s, cinco critérios verdes |

Nas duas, uma tentativa interrompida, identificada pelo órfão no scratch. Na B2 o kill caiu 10 s
dentro de uma tentativa de ~21 s, e o desfecho saiu 30 s depois do início: a segunda tentativa
começou logo depois do kill, à frente das 352 prontas. Se tivesse ido para o fim, esperaria ~24 min,
a drenagem inteira. Pela leitura fixada, as duas posições estão no começo (≤ 4), a B2 teve todos os
intervalos ≤ 1800 s e a contagem sempre zero. Maior intervalo entre amostras: 6 s nas duas.

O veredito se apoia na **posição**, e não no limiar. A B2 drenou em 24 min, e não nos ~35 que a
conta de 21 s por Vídeo previa. Por isso, mesmo recolocada no fim, a entrega teria esperado menos
que os 30 min, e a B2 sozinha não teria flagrado o limite. O que o afasta é a entrega voltar ao
começo nas duas rodadas, com mais de 350 mensagens prontas atrás dela: a espera não cresce com o
pico.

A posição, como foi fixada, erra para cima. Ela conta também o que as outras réplicas pegaram
durante a própria reentrega: na B2 são ~20 s com 3 réplicas, o que sozinho já dá ~3. Então
"posição 3" não quer dizer três entregas à frente, mas não muda a leitura de começo contra fim.

A evidência de cada rodada está em `scripts/carga/saida/<rótulo>/`, fora do git. Lá ficam
`terminal.log`, com a saída completa, `fila-no-kill.txt`, `drenagem.txt` e o resultado das
tentativas interrompidas. O script da rodada A não escreveu esses arquivos: foram gravados depois,
a partir da saída do terminal. O mesmo vale para o `censo.txt` da A, que foi refeito.

### Desvios do que foi fixado

- **A rodada B, como escrita, saiu inválida.** Com `FIAPX_VUS=200`, o k6 morreu com código 137 antes
  do primeiro `202`: cada VU carrega uma cópia de 41 MB, e o host tem 7 GB de RAM. O portão 0
  barrou a rodada (zero tentativas interrompidas), e a saída ficou em `113-b-carga2min`. A B2 é a
  mesma rodada com `FIAPX_VUS=10`. O que a pergunta precisa é o backlog no kill, e ele foi de 352.
- **A consulta final da rodada A falhou.** `date --iso-8601=ns` sai com vírgula decimal na
  localidade do host, e o Postgres recusou o instante. A drenagem já tinha terminado. A mesma
  consulta foi refeita à mão, com ponto decimal, sobre os arquivos da rodada, e o script passou a
  formatar o instante com ponto antes da B. Por isso a rodada A não imprimiu o veredito dos
  critérios 1 a 5. O censo refeito deu 400 `CONCLUIDO`.

### O que foi atualizado

O texto do limite em `alertas.yaml` (regra 4), ao lado do limiar no `application.properties` do
`videos`, no runbook de resgate (texto do 107, que citava este ticket), numa seção
`## Correção (113)` no fim do 106 e num ponteiro de uma linha sob a entrada do 106 no mapa.

### Revisão

`/code-review` em dois eixos. Depois dela:

- o mapa ganhou o ponteiro sob a entrada do 106;
- o script passou a gravar a fila no kill em arquivo;
- entrou a ressalva sobre o que a posição conta;
- no `oraculo.sh`, o `censo` passou a usar o mesmo `psql_videos`, e o carregamento de ids deixou de
  se repetir;
- o `presos` perdeu o parâmetro de limiar, que ninguém passava;
- os arquivos de saída ganharam nomes que não diferem só no gênero;
- a linha da drenagem passou a sair de um só lugar.

Ficou sem mudança, de propósito, o `if` por modo no `conservacao.sh`, que já é a forma do arquivo.

Uma rodada curta validou o script editado. Foi a `113-validacao-pos-revisao`, igual à A, em
2026-09-15: cinco critérios verdes, 400/400 em 81 s e uma tentativa interrompida. Ela voltou na
posição 2 com 384 prontas, desfecho em 1 s e zero presos. É o terceiro ponto no começo da fila, e ele
não entra na leitura fixada.

### Limites que ficam

- **`nack` com requeue não medido.** A falha transitória do `extracao` volta pela
  `failure-strategy=requeue`, que é outro caminho de retorno ao broker. Esta medição só cobre a
  entrega devolvida pelo fechamento do canal.
- **Os 420 s por tentativa continuam sem teto duro**, porque download e upload não têm timeout.
- **Uma réplica derrubada por rodada, uma tentativa interrompida em cada.** A mesma tentativa
  derrubada duas vezes não foi exercitada; pelo mecanismo medido, cada devolução volta ao começo.
- Sem a stack de observabilidade, a contagem de presos foi a do predicado do gauge feita no
  Postgres, e não a série exportada.

## Correção (119)

O caminho que faltava foi medido pelo [ticket 119](119-posicao-do-nack-com-requeue-sob-pico.md).
Numa rajada de 400 Vídeos válidos, com uma réplica do `extracao`, a falha transitória injetada no
MinIO produziu `redeliver` `0 -> 1` (delta 1). O snapshot do primeiro `nack` tinha 397 mensagens
prontas e a candidata identificada voltou ao começo: posição 0 pelo mesmo oráculo do ticket 113.
Ela terminou 15 s depois de `iniciada_em`, sem nenhum preso em `PROCESSANDO`; os 400 Vídeos
chegaram a `CONCLUIDO`, sem `FALHOU`, e as filas `extracao.extrair.dlq` e
`extracao.extrair.estacionamento` ficaram vazias.

O item deixa de ser um limite não medido para o regime ensaiado. Continua sem teto duro o relógio
de 420 s, porque download e upload não têm timeout próprio, e o ensaio identificou uma única
tentativa em voo por vez; não se extrapola a posição medida para múltiplas réplicas ou para
repetidas falhas na mesma entrega.
