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

## Método, fixado antes de rodar

Esta seção foi escrita **antes** da primeira medição, numa sessão de interrogação do enunciado
acima (2026-08-25/26), e é o pré-registro: o valor dela vem inteiro de existir com carimbo
anterior ao número. Ela **desvia do enunciado em sete pontos**, cada um com o motivo ao lado.
Onde diverge, vale esta seção.

### 1. A calibração mira em N=6, não em N=1

O enunciado manda calibrar a quantidade de Vídeos para a drenagem em `N=1` durar ~8 min. A
conta desmonta isso: com tempo de serviço na casa de 5 s, 8 min são ~96 Vídeos de 41 MB —
**~3,9 GB injetados por corrida**, cuja injeção leva dezenas de segundos. Em `N=1` isso é
ruído; em `N=6`, onde a drenagem inteira dura ~80 s, sobra uma janela de regime de ~40 s e uma
dúzia de Vídeos. Medir com a menor amostra justamente o ponto onde a curva deve entortar é o
pior arranjo possível.

**Alvo: ~12 min em `N=1`**, o que dá ~2 min em `N=6` e janela de regime de ~80 s. A quantidade
de Vídeos é **constante nos quatro pontos**, congelada pela calibração inicial e gravada na
saída — variá-la por ponto para manter a drenagem constante quebraria a comparação, porque
backlogs diferentes têm perfis de contenção diferentes.

### 2. Vazão é a série de `finalizado_em`, não o cronômetro de parede

Não existe backlog pré-carregado de verdade: as réplicas consomem assim que a primeira
mensagem cai, e parar o consumo exigiria bootar depois — proibido pelo item 5. A rampa de
injeção entra no denominador **penalizando mais o `N=6`**, que drena enquanto ainda se injeta,
o que enviesa a curva contra a linearidade que o ticket julga.

    vazão = (terminais na janela) / (último finalizado_em − primeiro finalizado_em após o fim da injeção)

O cronômetro de parede (primeiro `202` até o último terminal) é publicado **ao lado**, como
controle. A coluna `finalizado_em` já existe no esquema; nada precisa ser instrumentado.

### 3. A calibração mede a partição do tempo de serviço

O enunciado escolhe o fixture de 2 min argumentando que vídeo leve mediria overhead de
mensageria. A premissa não está estabelecida: o [ticket 006](006-ffmpeg-extracao.md) mediu
**5 min de 720p → ~7 s de `ffmpeg`**, o que por regra de três dá **~3 s** para o fixture de
2 min — contra 41 MB descidos do MinIO, ~120 PNGs (~22 MB) zipados e subidos de volta. Se o
tempo de serviço for majoritariamente I/O contra um **MinIO singleton**, a curva achata por
contenção num recurso compartilhado, indistinguível no gráfico de um limite do
*competing consumers*. Alongar o fixture não conserta: `ffmpeg` e bytes crescem juntos com a
duração.

Um passo da calibração mede a partição (download / `ffmpeg` / zip / upload) e ela é publicada
como **a lente de leitura da curva**. Sem ela o resultado é ilegível.

### 4. `down -v` antes de cada ponto

Decisão que o [025](025-carga-conservacao.md) deixou explicitamente para cá: nenhuma rodada
dele zerava o banco, e a suspeita para a latência que triplicou entre rodadas idênticas
(3,3 s → 9,8 s) é estado acumulado. Comparar vazão entre `N=1` e `N=6` com banco, bucket e
filas carregando lixo das corridas anteriores é comparar duas máquinas diferentes. `down -v`
é o único que zera também filas e scratch; o boot é ruído desprezível contra 12 min de
drenagem. A ordem dos pontos é randomizada como reforço, não como substituto.

**`down -v` destrói volumes do Compose** — Postgres, MinIO e RabbitMQ. Quem roda precisa saber.

### 5. Protocolo de boot, por causa do defeito 3 do 025

`limparOrfaosNoBoot` apaga *todos* os filhos de `/var/fiapx/extracao`, e o volume
`fiapx-extracao-scratch` é **um só para as N réplicas**. O defeito é de *boot*, não de regime:
basta que nenhum container suba com trabalho em voo. Logo — as N réplicas sobem e ficam
`healthy` **antes** da injeção, nada boota depois, e a corrida **aborta se qualquer container
reiniciar** durante a medição. O número de restarts observados é publicado por ponto.

Sem esse guarda, um restart silencioso vira um ponto achatado que se atribui à saturação do
host. O contorno não corrige nada: o defeito 3 continua sendo do [027](027-melhorias-medidas.md).

### 6. Repetições e dois controles finais

O 025 viu med 3,3 s contra med 9,8 s em duas rodadas de **mesma configuração**: uma corrida por
ponto não sustenta afirmação sobre linearidade, porque a variância entre corridas idênticas
pode superar o efeito procurado. **2 repetições por ponto**, valores brutos individuais
publicados, mediana usada na curva, dispersão reportada como parte do achado.

Ao fim da varredura, **duas corridas de controle em `N=1`**:

| Controle | Isola |
|---|---|
| limpo (`down -v` antes) | deriva de máquina — se não bater com o `N=1` inicial, a varredura inteira está contaminada, e o resultado é esse |
| **sujo** (sem `down -v`, sobre o lixo da varredura inteira) | estado acumulado: a diferença contra o limpo **é** a medição da degradação que o 025 suspeitou e não explicou |

O 026 mede o **tamanho** do efeito. Encontrar a causa (`EXPLAIN` da listagem, contagem de
objetos no MinIO, profundidade de DLQ) é do 027, e só se o efeito for grande.

### 7. Telemetria de host, senão o teto é inverificável

O enunciado declara que a curva vai achatar por saturação do host e que isso é achado
declarado, não ruído — mas nada no harness mede o host. Sem telemetria, *"achatou porque os
20 cores acabaram"* é exatamente o tipo de frase que o 025 se recusou a aceitar sobre
conservação. `docker stats` (CPU por container) e load average amostrados a cada 5 s durante
cada corrida; CPU média do conjunto `extracao` e da infra publicadas ao lado de cada ponto.

É o que permite dizer **qual** das três coisas achatou a curva: as réplicas batendo no próprio
teto de 2 CPUs, a infra passando fome, ou nenhuma das duas — que seria o achado mais
interessante, porque apontaria para a contenção de I/O do item 3.

### Portões de validade, por ponto

O 025 aprendeu na marra que "terminal" não basta: precisou inventar o critério 5 no meio da
execução porque um `ARQUIVO_INVALIDO` rápido *acelera* a drenagem. Vazão é a métrica que
**premia trabalho mal feito**, então cada ponto só conta como medição válida com:

1. zero respostas não-`202`;
2. zero presos (`RECEBIDO` ou `PROCESSANDO`);
3. zero `FALHOU` — o fixture é h264 válido;
4. **`quantidade_frames = 120` em todos os Vídeos** — a coluna já existe, e um Vídeo que
   "terminou" com 3 frames fez outro trabalho;
5. zero restarts de container (item 5).

Ponto que reprova é descartado e recorrido **uma** vez; se repetir, entra na publicação
**marcado como inválido**, com o motivo. Ponto inválido promovido em silêncio é como se produz
uma reta bonita e falsa.

### Dois gatilhos condicionais, pré-registrados

Fixados aqui para que não virem *"resolvi acrescentar um ponto depois de ver o número"*:

- **Fixture longo em `N=1` e `N=6`** se a partição do item 3 mostrar `ffmpeg` **< 40%** do
  tempo de serviço. Dois pontos bastam para comparar inclinação e responder se a curva muda de
  forma quando a proporção muda.
- **Ponto `N=3`** se a eficiência quebrar entre `N=2` e `N=4` — o intervalo mais largo, onde a
  varredura não distingue "entortou em 3" de "entortou em 4".

### O corte 025/026 errou por uma peça

O enunciado manda dizer se este ticket precisar construir. Precisa: `conservacao.sh` não tem
modo de varredura, não calibra, não computa vazão, não zera estado entre pontos e não amostra o
host. Entra um `scripts/carga/escalabilidade.sh`, irmão do `conservacao.sh`; `oraculo.sh`,
`injetor.js` e `gera-fixtures.sh` são reusados **sem um toque**.

O corte protegeu o que importava — a fatia cara (k6, oráculo, overlay, fixtures) ficou sozinha
no 025 e o 026 não reconstrói nada dela. Mas *"não constrói nada"* era otimismo: um experimento
com critério e denominador próprios pede orquestrador próprio.

### Fora deste ticket

Escalar a **borda**: a Q10 da interrogação tirou daqui e criou o
[028](028-escala-da-borda.md). Outro objeto (HTTP, não fila), outro critério (recusa e
latência, não vazão de drenagem) e construção nova de verdade. Fixar 2 réplicas de `videos`
atrás de proxy "para o `videos` não ser gargalo" também está fora: mudaria o objeto medido no
meio do argumento, e a telemetria do item 7 dirá com número se ele chegou perto de sê-lo.

**Nenhum ADR.** Método de medição é reversível e barato — o 027 já registra esse mesmo
julgamento. Se a partição do item 3 condenar o desenho (por exemplo, "o worker baixa o Vídeo
inteiro antes de extrair"), isso desce ao 027 como candidato **de vazão**, e é lá que se decide
se paga ADR.

### Ordem de execução

1. Escrever `scripts/carga/escalabilidade.sh`.
2. **Modo seco**: calibração + um único ponto `N=1`. Se o tempo de serviço ou a partição
   desmentirem a conta do 006, metade das decisões acima merece revisão antes de gastar
   ~1 h 20 de máquina.
3. Varredura completa — disparada por quem é dono da máquina, por causa do `down -v`.
4. `docs/pesquisa/carga-escalabilidade.md`, as edições no `docs/arquitetura.md`, a resolução
   aqui, e os candidatos de vazão no 027.

### A saída

`docs/pesquisa/carga-escalabilidade.md`, no padrão do
[`ffmpeg-extracao.md`](../../pesquisa/ffmpeg-extracao.md) — método **transcrito antes** dos
números, incluindo os gatilhos acima com a data do pré-registro. Por ponto: N, `cpus` por
réplica, Vídeos, vazão de regime, vazão de parede, eficiência de escala, valores brutos das
repetições e mediana, CPU média de réplicas e infra, restarts, portões. Mais: gráfico ASCII de
eficiência × N, a partição do tempo de serviço, os dois controles finais, e uma seção do que a
medição **não** mostrou — que foi o que deu ao 025 a metade mais honesta da resolução.

No `docs/arquitetura.md`: subseção sobre linearidade dentro de § *O que a medição mostrou*, a
célula *"dobrar réplicas dobra a vazão"* da tabela § *O que escala, e como* reescrita com o
intervalo medido, e a *Limitação conhecida* trocada de "continua argumentada, não medida" para
"medida até N=X nesta máquina, e além disso não sei" — que é uma limitação melhor.
