<!-- label: wayfinder:research -->
# Medição: linearidade horizontal do serviço `extracao`

- Ticket: [`026-linearidade-horizontal`](../wayfinder/tickets/026-linearidade-horizontal.md)
- Data: 2026-08-26
- Máquina: Debian 13, 20 vCPU, 62 GB RAM, NVMe; Docker Compose; imagens `latest` do GHCR
- Harness: `scripts/carga/escalabilidade.sh` (novo) sobre `injetor.js`, `oraculo.sh`,
  `gera-fixtures.sh` e `docker-compose.carga.yml` do [ticket 025](../wayfinder/tickets/025-carga-conservacao.md)

A afirmação sob julgamento é uma célula da tabela § *O que escala, e como* de
[`docs/arquitetura.md`](../arquitetura.md): **"competing consumers puro: dobrar réplicas
dobra a vazão"**. O [ticket 025](../wayfinder/tickets/025-carga-conservacao.md) mediu a outra metade — conservação
sob pico — e deixou a escalabilidade *argumentada, não medida*.

---

## 1. Método, transcrito antes dos números

Esta seção é cópia fiel do pré-registro que está na seção *Método, fixado antes de rodar* do
ticket 026, escrita em **2026-08-25/26**, antes da primeira medição. O valor dela vem inteiro
de existir com carimbo anterior ao número — sem isso, "o critério era 0,80" é narrativa
pós-fato.

| # | Decisão pré-registrada |
|---|---|
| Forma | Backlog de Vídeos injetado de uma vez; vazão é a taxa de drenagem, sem controle de taxa |
| Fixture | `carga-2min.mp4` — 720p, 120 s, 41 MB, 120 frames a 1 fps |
| Pontos | `N ∈ {1, 2, 4, 6}` réplicas do `extracao`, `cpus=2` por réplica |
| Quantidade de Vídeos | Por calibração, mirando **~12 min de drenagem em `N=1`**; **constante nos quatro pontos** |
| Denominador | Série de `finalizado_em` na janela **pós-injeção**; cronômetro de parede publicado ao lado como controle |
| Isolamento | `docker compose down -v` antes de cada ponto; ordem dos pontos randomizada |
| Protocolo de boot | As N réplicas sobem e ficam `healthy` **antes** da injeção; corrida aborta se qualquer container reiniciar |
| Repetições | 2 por ponto, brutos publicados, mediana na curva |
| Controles finais | Duas corridas em `N=1`: uma limpa (deriva de máquina) e uma **suja** (estado acumulado) |
| Telemetria | `docker stats` e load average a cada 5 s |
| Critério | Eficiência de escala `vazão(N) / (N × vazão(1)) >= 0,80`; o experimento **relata** o primeiro N em que quebra, não reprova ali |

### Portões de validade, por ponto

Vazão é a métrica que **premia trabalho mal feito** — um `ARQUIVO_INVALIDO` rápido *acelera*
a drenagem. O 025 aprendeu isso na marra e precisou inventar um quinto critério no meio da
execução. Aqui os cinco estão fixados antes:

1. zero respostas não-`202`;
2. zero presos (`RECEBIDO`, `PROCESSANDO` ou ausente do banco);
3. zero `FALHOU` — o fixture é h264 válido;
4. `quantidade_frames = 120` em **todos** os Vídeos;
5. zero restarts de container.

Ponto que reprova é descartado e recorrido **uma** vez; se repetir, entra na publicação
marcado como inválido, com o motivo.

### Dois gatilhos condicionais, pré-registrados

- **Fixture longo em `N=1` e `N=6`** se a partição do tempo de serviço mostrar `ffmpeg`
  **< 40%** — porque então a curva mediria o MinIO singleton, não *competing consumers*.
- **Ponto `N=3`** se a eficiência quebrar entre `N=2` e `N=4`.

---

## 2. Resumo executivo

| Pergunta | Resposta |
|---|---|
| "Dobrar réplicas dobra a vazão"? | **Sustenta-se até `N=6` nesta máquina.** Eficiência 0,99 / 0,90 / 0,88 em `N` = 2 / 4 / 6 — nunca abaixo do critério de 0,80 |
| Onde a curva entorta | Entre `N=2` e `N=4`: de 0,99 para 0,90. De `N=4` para `N=6` a perda quase para (0,90 → 0,88) |
| O teto do laboratório foi alcançado? | **Não, mas quase.** Em `N=6` o host fica a **77%** (~15,4 dos 20 núcleos); o que morde é o `cpus=2` por réplica, e a folga acabaria em `N=8` |
| Do que é feito o tempo de serviço | **`ffmpeg` é 98,2%** — a curva mede *competing consumers*, não o MinIO singleton |
| A degradação por estado acumulado que o 025 suspeitou | **Não existe**: limpo 0,0498 contra sujo 0,0499 Vídeo/s, sobre três corridas de lixo acumulado |
| Vazão absoluta | 2,96 Vídeo/min em `N=1`; **15,6 Vídeo/min em `N=6`** (Vídeo de 2 min, 720p, 120 frames) |
| Achado colateral, de vazão | `ffmpeg -threads 2` sob cota de 2 CPUs é **32% mais rápido** que o default — candidato para o [027](../wayfinder/tickets/027-melhorias-medidas.md) |

---

## 3. A partição do tempo de serviço

O item 3 do pré-registro existia porque a conta puxada do [ticket 006](ffmpeg-extracao.md)
sugeria **~3 s de `ffmpeg` contra ~63 MB de I/O** por Vídeo. Se fosse assim, a curva mediria
contenção num MinIO singleton, e um achatamento seria indistinguível de um limite do desenho.

Medido dentro do próprio container do `extracao` (`ffprobe`, `ffmpeg` e empacotamento) e com um
`mc` na mesma rede (transferências), `[medido]`:

| Etapa | Segundos | Fatia |
|---|---:|---:|
| download do Vídeo (41 MB) | 0,07 | 0,3% |
| `ffprobe` (duração + stream) | 0,20 | 1,0% |
| **`ffmpeg` (120 frames a 1 fps)** | **20,19** | **98,2%** |
| empacotamento (~22 MB) | 0,02 | 0,1% |
| upload do Pacote | 0,08 | 0,4% |
| **soma** | **20,57** | |

O tempo de serviço medido ponta a ponta pela série de `finalizado_em` é **21,6 s** — as etapas
somam 95% dele, o que é a conferência de que a partição não está deixando nada grande de fora.

**O gatilho condicional do fixture longo não dispara**: ele exigia `ffmpeg` abaixo de 40%.

### Por que a conta do ticket 006 errou por 7×

O 006 mediu *5 min de 720p → ~7 s*. Aqui 2 min de 720p custam 20 s. A diferença **não** é o
conteúdo do fixture — é o teto de CPU, e ela reproduz na hora `[medido]`:

| Onde | Tempo de `ffmpeg` no mesmo fixture de 2 min |
|---|---:|
| host, sem teto (20 núcleos) | **3,04 s** |
| host, `taskset -c 0,1` (2 núcleos dedicados) | 14,02 s |
| container, `cpus=2`, `-threads` default | **20,84 s** |
| container, `cpus=2`, `-threads 2` | **14,14 s** |

Os 3,04 s do host escalam para ~7,6 s em 5 min: **o número do 006 estava certo** — para uma
máquina sem teto. O que ele não previa é que o mesmo trabalho dentro de uma cota de 2 CPUs
custa 6,8× mais.

E a última linha é o achado que sai daqui como candidato de vazão: `nproc` dentro do container
devolve **20**, então o `ffmpeg` default abre 20 threads que a cota do CFS estrangula em bloco.
Dizer `-threads 2` recupera **32%**, e o resultado bate quase exatamente com os 2 núcleos
dedicados do `taskset` — isto é, a perda inteira era sobre-assinatura de threads contra a cota.

---

## 4. A curva

Vazão de regime, mediana de duas repetições válidas por ponto:

| N | Vídeos | Vazão (Vídeo/s) | Vídeo/min | Eficiência `vazão(N)/(N×vazão(1))` |
|---:|---:|---:|---:|---:|
| 1 | 32 | 0,0493 | 2,96 | 1,00 |
| 2 | 32 | 0,0979 | 5,87 | **0,99** |
| 4 | 32 | 0,1779 | 10,67 | **0,90** |
| 6 | 32 | 0,2603 | 15,62 | **0,88** |

```
eficiencia de escala x N   (criterio pre-registrado: >= 0,80)

 1,0 |####################################################  0,99 (N=2)
     |
 0,9 |#################################################     0,90 (N=4)
     |################################################      0,88 (N=6)
 0,8 +- - - - - - - - - - - - - - - - - - - - - - - - - -  criterio
     |
 0,0 +--------+--------+--------+--------+--------+------
      N=1      N=2               N=4               N=6
```

**A eficiência não quebra 0,80 em nenhum ponto medido.** O segundo gatilho condicional — ponto
`N=3` se a eficiência quebrasse entre `N=2` e `N=4` — **não dispara**, apesar de o joelho estar
visivelmente ali (0,99 → 0,90). Acrescentar `N=3` agora seria exatamente o
*"resolvi acrescentar um ponto depois de ver o número"* que o pré-registro existe para impedir;
localizar o joelho com precisão fica como trabalho de quem quiser abrir um ticket para isso.

### Brutos, por corrida

| Rótulo | N | Injeção | Drenagem | Terminais na janela | Janela | Vazão de regime | Vazão por intervalos | Vazão de parede |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `n1-r1` | 1 | 17 s | 678 s | 32 | 652,65 s | 0,0490 | 0,0475 | 0,0468 |
| `n1-r2` | 1 | 17 s | 669 s | 32 | 645,07 s | 0,0496 | 0,0481 | 0,0474 |
| `n2-r1` | 2 | 18 s | 355 s | 32 | 334,19 s | 0,0958 | 0,0928 | 0,0876 |
| `n2-r2` | 2 | 17 s | 344 s | 32 | 320,01 s | 0,1000 | 0,0969 | 0,0910 |
| `n4-r1` | 4 | 21 s | 201 s | 32 | 178,96 s | 0,1788 | 0,1732 | 0,1504 |
| `n4-r2` | 4 | 20 s | 207 s | 32 | 180,88 s | 0,1769 | 0,1714 | 0,1493 |
| `n6-r1` | 6 | 21 s | 145 s | 32 | 121,82 s | 0,2627 | 0,2545 | 0,2027 |
| `n6-r2` | 6 | 21 s | 146 s | 32 | 124,02 s | 0,2580 | 0,2500 | 0,2007 |

A dispersão entre repetições da mesma configuração é de **1,1% a 4,4%** — muito menor do que o
efeito procurado, o que é a condição para a curva significar alguma coisa. Isso contrasta com o
que motivou o item 6: o 025 viu mediana 3,3 s contra 9,8 s em duas rodadas idênticas. Sobre isso,
ver a §6.

### Duas correções ao denominador, ambas pré-registradas e ambas confirmadas

**A janela pós-injeção não é preciosismo.** A coluna *vazão de parede* é o mesmo experimento
medido do primeiro `202` ao último terminal, e a rampa de injeção de 32 × 41 MB entra nela
penalizando quem drena rápido:

| N | Eficiência (regime) | Eficiência (parede) |
|---:|---:|---:|
| 2 | 0,99 | 0,95 |
| 4 | 0,90 | **0,80** |
| 6 | 0,88 | **0,71** |

Pelo cronômetro de parede a afirmação **reprovaria** em `N=6` — e reprovaria por um artefato do
instrumento, não do sistema: em `N=6` a drenagem inteira dura 145 s, dos quais 21 s são injeção.
O item 2 do pré-registro previu esse viés e o descontou **antes** de ver o número; é a decisão de
método de maior efeito no resultado.

**A fórmula pré-registrada superestima em 1/(n−1).** Entre `n` eventos há `n−1` intervalos, e a
fórmula divide a contagem pelo *span*. A coluna *vazão por intervalos* é a variante corrigida,
publicada como teste de sensibilidade. Ela desloca todos os pontos para baixo em ~3%, e como
`n = 32` em todos, o deslocamento é uniforme: **a eficiência sai idêntica** (0,99 / 0,90 / 0,88).
O viés existia e não importou.

---

## 5. Telemetria de host: quem é o teto

| N | CPU por réplica do `extracao` | CPU total do `extracao` | CPU média do `videos` | MinIO | Load average |
|---:|---:|---:|---:|---:|---:|
| 1 | 194–198% | ~2,0 núcleos | 18–22% | 8% | 6,3 |
| 2 | 188% | ~3,8 núcleos | 47–48% | 18% | 9,9 |
| 4 | 180–182% | ~7,2 núcleos | 74–81% | 20–25% | 15,2 |
| 6 | 169–172% | ~10,2 núcleos | 91–95% | 30–36% | 22,9 |

Os valores por container acima são **recalculados** a partir do `telemetria.txt` bruto
descartando a primeira amostra de cada container (ver "artefatos" no fim desta seção); a coluna
`CPU_EXT` de `pontos.psv`, gravada durante a corrida, ainda traz a versão sem esse desconto e
sai ~3 a 7 pontos mais baixa. O harness já foi corrigido para as próximas corridas.

E a medição que decide a pergunta, de uma corrida `N=6` **suplementar** feita depois da varredura
só para isto (`suplementar-n6-host/`, vazão 0,2589 — dentro dos 0,2580–0,2627 dos dois pontos
publicados, o que é a terceira confirmação independente do ponto):

> **Ocupação do host durante a drenagem em `N=6`: 74–78%, ou ~15,4 dos 20 núcleos** `[medido]`,
> lido do `/proc/stat` e amostrado a cada 5 s.

A leitura: **o host não saturou, mas a folga é menor do que a soma por container sugere.** Os
containers somam ~11,6 núcleos (`extracao` 10,2 + `videos` 0,9 + MinIO 0,3 + o resto, **sem** o
RabbitMQ, cuja leitura é inutilizável — ver adiante) e o host marca 15,4: cerca de 4 núcleos são
trabalho que nenhuma medida por container captura, entre kernel, rede, I/O, runtime e o próprio
broker. Com `N=6` × `cpus=2` = 12 núcleos
de cota contra 4,6 núcleos livres, **`N=8` é onde a folga acabaria** — e é por isso que a
afirmação honesta é *"linear até N=6 nesta máquina"*, não *"linear"*.

A perda de 12% em `N=6` tem uma assinatura clara e ela **não** é o host: a CPU por réplica cai de
~196% para ~170%, isto é, cada réplica deixa de conseguir gastar a cota que tem. Combina com o
achado da §3 — cada `ffmpeg` abre 20 threads, então em `N=6` há ~120 threads disputando 20
núcleos sob seis cotas independentes. O load de 22,9 conta essas threads executáveis e por isso
supera 20 sem que o host esteja saturado. A sobre-assinatura é a hipótese mais provável para os
12%, e é **testável**: se `-threads 2` derrubar a perda, a causa era essa. Fica para o 027.

### Um número deste instrumento não é publicável

A CPU do container do **RabbitMQ** lida por `docker stats` oscila entre **0,3% e 894% em amostras
de 2 s** sem nenhuma correlação com trabalho — enquanto o `extracao` consumia firme a 200% e a
fila estava praticamente vazia. Ela não entra em nenhuma tabela acima. Dois outros artefatos do
mesmo instrumento foram encontrados e descontados:

- a **primeira** amostra de cada container reporta o acumulado desde o boot (1455% e 1547% para o
  Keycloak, medidos), e por isso as médias desta seção descartam a primeira amostra de cada um;
- o **pico** de ~1200% do `videos` **não** é artefato: foi reconfirmado a 2 s de resolução, numa
  stack sem boot recente, com quatro amostras consecutivas em 1096%, 1342%, 1275% e 1202%
  durante os 8 s de injeção `[medido]`.

## 6. Os dois controles finais

| Controle | Vazão de regime | Leitura |
|---|---:|---|
| limpo (`down -v` antes) | 0,0498 | Bate com `n1-r1` (0,0490) e `n1-r2` (0,0496): **a varredura não derivou** ao longo das ~2 h |
| **sujo** (sem `down -v`, sobre 3 corridas de lixo) | **0,0499** | Diferença de **0,2%** contra o limpo |

O controle sujo é o que mais valia a pena rodar, e o resultado é um **não**: 96 Vídeos e 96
Pacotes acumulados no MinIO, 96 linhas na tabela `video` e as filas já usadas **não custam vazão
mensurável**. A degradação que o 025 suspeitou e não explicou (mediana 3,3 s contra 9,8 s em
rodadas idênticas) **não é estado acumulado**. A §7 mostra qual é a explicação mais provável.

---

## 7. O que quase produziu uma curva falsa

Duas das doze corridas foram corrompidas pela **suspensão do host** — a máquina dormiu no meio da
medição, 708 s numa e 2695 s noutra. O efeito é perverso porque não quebra nada: nenhum Vídeo se
perde, nenhum falha, nenhum container reinicia. O relógio anda e o denominador estica.

- `n1-r2-descartado` marcou **0,0239 Vídeo/s**, metade dos outros `N=1`, e **passou nos cinco
  portões pré-registrados**. Se tivesse entrado na mediana, a base de `N=1` cairia para 0,0365 e
  toda a curva apareceria com eficiência **acima de 1,0** — o gráfico mais bonito e mais falso
  possível.
- `controle-sujo-descartado` parou 45 min no meio: 25 de 32 presos e a drenagem estourando o
  limite. Este pelo menos reprovou os portões 2 e 4; sozinho, teria sido lido como "o estado
  acumulado trava o sistema", que é a conclusão errada.

A prova de que foi o host, e não o sistema: a série de `finalizado_em` mostra **20,7 s por Vídeo
antes do buraco e 21,5 s depois**, sem nenhuma degradação; e o buraco aparece igual na
amostragem de `docker stats`, que roda **fora** dos containers, e no `journalctl` do host, que
não registra nada entre 19:01:57 e 19:46:48 e volta com erros de rede — a assinatura de um
retorno de suspensão.

Daí sai um **sexto portão**, acrescentado depois dos cinco e por isso declarado como emenda:

> 6. **continuidade da série de telemetria** — com amostragem de 5 s, qualquer intervalo maior
>    que 30 s entre amostras consecutivas é ausência do host, e o ponto não vale.

Aplicado às doze corridas, ele separa exatamente as duas: os outros dez pontos têm intervalo
máximo de **8 s**, uniformemente. Os dois foram descartados, recorridos uma vez cada — como o
protocolo pré-registrado manda — e ficam publicados marcados como inválidos em
`scripts/carga/saida/escalabilidade/*-descartado/`.

Isto também é a explicação candidata para a variância de 3× que o 025 viu entre rodadas
idênticas e atribuiu a estado acumulado: o 025 não tinha telemetria de host, então não tinha como
distinguir "o sistema ficou lento" de "a máquina não estava lá".

---

## 8. O que a medição **não** mostrou

- **Onde a curva realmente entorta.** O joelho está entre `N=2` e `N=4`, e não há ponto `N=3`
  para localizá-lo. O gatilho pré-registrado não disparou porque a eficiência não quebrou 0,80.
- **Se a perda de 12% é sobre-assinatura de threads.** A hipótese é forte (a CPU por réplica cai
  junto com `N`, e `-threads 2` recupera 32% isoladamente), mas ela **não foi testada na
  varredura** — testá-la exigiria repetir os quatro pontos com o `ffmpeg` reconfigurado, o que é
  outro experimento.
- **Onde está o teto desta máquina.** Em `N=6` o host está a 77% e restam ~4,6 núcleos livres —
  menos que os 4 que um sétimo e oitavo par de réplicas pediriam. `N=8` é a extrapolação óbvia e
  **não foi medida**; é lá que a linearidade deveria acabar.
- **Nada sobre a escala da borda.** O `videos` rodou como réplica única em todos os pontos. Na
  drenagem ele é barato (91–95% de um núcleo em `N=6`), mas o pico medido durante a **injeção** é de
  **~1200%, ou 12 núcleos** `[medido]` — 16 uploads simultâneos de 41 MB. A média baixa esconde
  isso inteiramente, e é a borda sob rajada, não sob drenagem, que o
  [028](../wayfinder/tickets/028-escala-da-borda.md) tem de julgar.
- **Nada sobre outra máquina.** Todo número aqui vale para 20 vCPU e NVMe local. Num host onde o
  MinIO fosse remoto, a partição da §3 seria outra e a conclusão poderia inverter.
- **Nada sobre Vídeos heterogêneos.** Todos os 32 Vídeos de cada corrida são o mesmo arquivo. Uma
  fila com durações misturadas tem perfil de espera diferente, e *competing consumers* é
  justamente o desenho que lida bem com isso — mas não foi medido.

---

## 9. Como reproduzir

```sh
scripts/carga/escalabilidade.sh calibra      # tempo de servico, particao, congela os N Videos
scripts/carga/escalabilidade.sh seco         # calibracao + um unico ponto N=1
scripts/carga/escalabilidade.sh varredura    # a coisa toda, ~2 h  (roda `down -v`!)
scripts/carga/escalabilidade.sh resumo       # tabela, curva e eficiencia do que ja foi medido
```

**`varredura` e `ponto` destroem os volumes do Compose** (Postgres, MinIO, RabbitMQ, scratch) —
é o item 4 do método. E rode sob `systemd-inhibit --what=sleep:idle`, ou a §7 se repete.
