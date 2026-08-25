# Linearidade horizontal do extracao

- id: 026
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 025

## Question

Fecha a outra metade de **"a escalabilidade é argumentada, não medida"**
(`docs/arquitetura.md` § *Limitações conhecidas*). A afirmação sob julgamento é a da tabela
*§ O que escala, e como*: *"competing consumers puro: dobrar réplicas dobra a vazão"*.

**Não constrói nada.** Reusa o harness de [025](025-carga-conservacao.md) e varre um parâmetro.
O corte entre os dois tickets está na fronteira de *construção* justamente para que a fatia que
mais arrisca estourar a janela fique sozinha; se este ticket precisar escrever peça nova, o
corte estava errado e vale dizer isso na resolução.

### O experimento

**Forma: backlog fixo pré-carregado.** Enfileira N e cronometra a drenagem — vazão é
`N / tempo_de_drenagem`, uma divisão, sem controle de taxa para desconfiar. Carga *chegando*
misturaria a vazão do injetor com a do sistema. (Rampa até o joelho é o instrumento para achar
capacidade máxima, que não é a pergunta deste ticket.)

**Fixture: o de ~2 min**, não o controle de 3 s. Aqui interroga-se a vazão do worker, e vídeo
leve mediria overhead de mensageria — devolvendo uma linearidade falsamente boa. 2 min a 1 fps
são 120 frames: trabalho real, longe dos 4,4 GB que o ticket 006 mediu.

**N por calibração, não chutado.** Um passo do harness mede uma extração isolada e escolhe N
para a drenagem em `N=1` durar ~8 min. N fixo escrito no script apodrece na primeira máquina
diferente; N derivado de medição sobrevive — mesmo instinto do `smoke.sh`, verificar em vez de
supor.

**Pontos: `N ∈ {1, 2, 4, 6}` réplicas, `cpus=2` cada.** Quatro pontos bastam para uma reta e
para ver onde ela entorta. Pico de 12 cores dos 20 da máquina, deixando 8 para Postgres,
RabbitMQ, MinIO, `videos` e o próprio k6 — **sem essa margem, um achatamento em N=6 seria o
Postgres passando fome, indistinguível de um limite do desenho**. `cpus=2` e não 4 porque
`ffmpeg` com `fps=1` não paraleliza indefinidamente: 4 gastariam orçamento em threads ociosas
e fariam a curva parecer pior do que é.

### Critério, fixado antes de rodar

Eficiência de escala `vazão(N) / (N × vazão(1)) >= 0,8`. O experimento **relata** o primeiro N
em que ela quebra em vez de reprovar ali: 0,8 é convenção defensável, e o limiar importa menos
que o compromisso de fixá-lo antes. O valor está na curva inteira publicada, não no
passa/reprova.

### O teto do laboratório é resultado, não ruído

Tudo roda numa máquina, com toda a infra disputando os mesmos 20 cores: a curva vai achatar por
saturação do **host**, não por limite do desenho. Isso é aceito e **declarado**, não escondido —
os `cpus:` por réplica existem para tornar o orçamento explícito e a curva interpretável.
O `ffmpeg` **real** permanece: trocá-lo por carga sintética mediria o harness, não o sistema.
*"Linear até N=X nesta máquina, onde X é onde os 20 cores acabam"* é uma frase mais forte que
uma reta perfeita obtida com trabalho falso.

### Saída

Números brutos e curva em `docs/pesquisa/carga-escalabilidade.md` — mesmo padrão de
`docs/pesquisa/ffmpeg-extracao.md`, medição que virou decisão citada na arquitetura. A
*Limitação conhecida* em `docs/arquitetura.md` **não some**: muda de "não medi" para "medi até
aqui, e além disso não sei", que é uma limitação melhor.
