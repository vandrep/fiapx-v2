<!-- label: wayfinder:research -->
# Medição: escala da borda (`videos`)

- Ticket: [`028-escala-da-borda`](../wayfinder/tickets/028-escala-da-borda.md)
- Data: 2026-08-29
- Máquina: Debian 13 (devcontainer, Docker-outside-of-Docker), 6 vCPU, 15 GB RAM; imagens
  `latest` do GHCR (build de 2026-08-29 16:44, já com as quatro correções do
  [ticket 027](../wayfinder/tickets/027-melhorias-medidas.md))
- Harness: `scripts/carga/borda.sh` (novo) + `videos-proxy` em
  [`docker-compose.carga.yml`](../../docker-compose.carga.yml), sobre `injetor.js` e
  `gera-fixtures.sh` do [ticket 025](../wayfinder/tickets/025-carga-conservacao.md) sem
  alteração — só o alvo do injetor muda (o proxy, não o `videos` direto)

A célula sob julgamento é a do `videos` na tabela § *O que escala, e como* de
[`docs/arquitetura.md`](../arquitetura.md): hoje **"Nunca medido"**. O número que existe é o
que o [ticket 025](../wayfinder/tickets/025-carga-conservacao.md) mediu sem querer — derrubar a
réplica única durante uma rajada custou **361 envios recusados de 400**.

Máquina diferente das medições anteriores: 026/027 rodaram num host de 20 vCPU, esta sessão
roda numa de 6. Os números absolutos de vazão **não são comparáveis entre máquinas** — o que
importa aqui são as comparações **dentro** desta máquina (N=1 contra N=3, réplica única contra
N réplicas), que é o que cada pergunta pede.

---

## 1. O que foi construído

Ao contrário do 026, este ticket não podia reusar o harness existente: escalar `videos` por
réplicas quebra a publicação de porta (`8080:8080` não abre em N containers ao mesmo tempo), e
nenhuma peça existente falava com mais de uma réplica da borda.

| Peça nova | Papel |
|---|---|
| `videos-proxy` (nginx, em `docker-compose.carga.yml`) | proxy L7 na frente de N réplicas do `videos`; só existe no overlay de carga — a demo da banca continua sem proxy e com porta publicada direto pelo `videos` |
| `scripts/carga/proxy/entrypoint.sh` | gera o `nginx.conf` em runtime, porque N e o hostname de cada réplica só existem em runtime (upstream do nginx open-source resolve hostname uma vez, no boot — sem NGINX Plus não há resolução dinâmica) |
| `scripts/carga/borda.sh` (novo) | orquestra os dois modos abaixo, reusando `gera-fixtures.sh`/`injetor.js`/`oraculo.sh` sem tocar neles |

**Achado de instrumento, não de sistema.** Nesta sessão o `dockerd` real roda fora do
container onde o harness executa (Docker-outside-of-Docker) — os dois só concordam no
*conteúdo* de `/workspace`, não no caminho. Bind mount por caminho relativo resolve do lado do
daemon, que não tem `/workspace`; e qualquer coisa publicada em `localhost:PORTA` pelo host
real é inalcançável a partir de dentro do devcontainer (redes L3 diferentes — só a API do
Docker é compartilhada, via socket encaminhado). Duas consequências no harness:

- `borda.sh` monta com `--project-directory` e com o caminho real do host
  (`LOCAL_WORKSPACE_FOLDER`, que o devcontainer expõe), caindo em `raiz` quando essa variável
  não existe — mesmo comportamento de sempre num devcontainer local sem DooD.
- a checagem "amostra pela API" (que `oraculo.sh amostra` faz batendo em `localhost:8080`/
  `8081`) precisou rodar **dentro** de um container na rede do Compose, contra
  `videos-proxy:8080`/`keycloak:8080` — mesmo truque que o injetor já usava, agora também para
  o oráculo. `oraculo.sh` não foi tocado (continua certo para quem roda no mesmo host do
  `dockerd`); a função nova (`amostra_via_rede`) vive só em `borda.sh`.

Achado de instrumento **dentro** do proxy: o healthcheck do `videos-proxy` usava
`http://localhost:8080`, e a imagem `nginx:alpine` resolve `localhost` para `::1` primeiro. O
nginx aqui só escuta em IPv4 (`listen 8080`, sem `[::]`), então o healthcheck ficava
"unhealthy" para sempre mesmo com o proxy respondendo normalmente em `127.0.0.1`. Trocado para
`127.0.0.1` explícito.

### Como o proxy falha (e não falha)

`upstream videos_borda` lista as N réplicas pelo nome de container
(`<projeto>-videos-<índice>`, verificado por teste direto — não é `videos-<índice>`), com
`max_fails=1 fail_timeout=2s` e `proxy_next_upstream error timeout invalid_header http_502
http_503 http_504`. Bufferização de corpo fica no **default** (ligada) de propósito: é o que
permite ao nginx reencaminhar o `POST` inteiro para a próxima réplica quando a atual morre no
meio — desligá-la (a escolha natural para upload grande) quebraria exatamente a propriedade de
resiliência que este ticket mede.

---

## 2. Resumo executivo

| Pergunta | Resposta |
|---|---|
| N réplicas atrás do proxy aguentam mais envios simultâneos que uma? | **Sim, em latência de aceite.** Mediana do `202` caiu de **630 ms (N=1) para 114 ms (N=3)** sob 400 conexões simultâneas — 5,5× |
| A vazão de dreno também melhora com N? | **Não, nesta medição** — 400 concluídos em 46 s nos dois pontos. Quem limita o dreno é o `extracao` (3 réplicas, `cpus=1` cada, igual nos dois pontos), não o `videos`: a réplica extra da borda alivia o aceite, não tem o que acelerar depois dele |
| Matar uma réplica de N custa zero requisição? | **Não chega a zero, mas quase.** Com N=3, matar uma durante a rajada de 400 custou **39 recusados (9,75%)** — contra **361 de 400 (90,25%)** com réplica única. Redução de 9,3× na taxa de perda |
| Por que os 39 não são zero | Os 39 são **todos HTTP 502**, nenhum timeout/EOF/reset — o nginx recusa, por padrão, reencaminhar um `POST` (não-idempotente) para outra réplica depois de a conexão falhar esperando resposta, porque a réplica morta pode já ter completado o efeito colateral (linha gravada, upload feito) antes de morrer; reencaminhar arriscaria duplicar o Vídeo |
| Onde o gargalo deixa de ser a borda | Já não era, nesta medição, para fins de **vazão** — o `extracao` limita antes. Para **latência de aceite** sob concorrência, N=1 *era* o gargalo (mediana 5,5× pior) e N=3 dissolve isso. Não foi isolado se o próximo teto é Postgres, MinIO ou o próprio `extracao` — ver § Limitações |

---

## 3. Critério, fixado antes de cada rodada

Mesma regra dos tickets 025/026/027: limiar declarado antes de rodar, impresso no próprio
harness (`scripts/carga/borda.sh`, passo 2) antes da rajada. Portões herdados do 026 valem nos
dois modos: zero `FALHOU` com fixture válido, zero presos, `quantidade_frames` certo
(`= 3`, o fixture é `controle-3s.mp4` a 1 fps).

- **`escala`**: zero respostas não-`202` (não há falha injetada); o número relatado é
  vazão/latência, não passa/reprova.
- **`mata-replica`**: **zero respostas não-`202` é o critério central**, e ao contrário do
  `mata-videos` do `conservacao.sh` (réplica única, onde o critério 1 é dispensado de
  propósito porque a queda *é* o efeito procurado), aqui ele **não é dispensado** — é
  exatamente o que "matar uma réplica deveria custar zero requisição" está pedindo para medir.
  Réplicas: N=3 (o mínimo que ainda deixa 2 vivas depois da queda, com folga de leitura).

---

## 4. `escala`: N réplicas seguram mais rajada?

Rajada de 400 envios / 400 conexões simultâneas de `controle-3s.mp4` (fixture de 988 KB, 3 s,
mesma escolha do 025 — a rajada interroga a borda e a fila, não o `ffmpeg`), `extracao` fixo
em 3 réplicas × `cpus=1` nos dois pontos, para isolar a variável sob teste.

| N réplicas `videos` | Aceitos | Latência do `202` (med / p95 / max) | Drenagem completa |
|---:|---:|---:|---:|
| 1 | 400/400 | 630 ms / 4040 ms / 5091 ms | 400 em 46 s |
| 3 | 400/400 | **114 ms** / 2870 ms / 3443 ms | 400 em 46 s |

Todos os seis portões passaram nos dois pontos (zero recusa, zero preso, zero `FALHOU`,
frames certos, amostra pela API ok).

A mediana caindo 5,5× é o sinal mais forte — sob 400 conexões simultâneas, uma réplica só
enfileira internamente antes de conseguir atender, e três dividem essa fila de espera entre
si. O p95 e o máximo melhoram bem menos (4040→2870 ms, 5091→3443 ms): a cauda longa não é
exclusiva de N=1, o que sugere que outra coisa — Keycloak sob a mesma rajada de tokens, ou o
próprio host de 6 núcleos sob pico — contribui para os piores casos nos dois pontos por igual.
Isso não foi isolado (ver § Limitações).

**A vazão de dreno é idêntica porque a pergunta certa, aqui, não é sobre o `videos`.** Uma vez
aceito, o Vídeo sai da responsabilidade da borda; quem determina quando ele termina é o
`extracao`, que não mudou entre os dois pontos. Aumentar réplicas do `videos` não tinha como
mexer nisso — e não mexeu. Este é o mesmo argumento que já apareceria olhando o `AGENTS.md`
("o gargalo real é o `extracao`"), agora com número ao lado para o lado da borda: ela contribui
**latência de aceite**, não throughput de sistema.

---

## 5. `mata-replica`: matar uma de N custa zero requisição?

Rajada de 400 envios / 400 conexões simultâneas, N=3, uma réplica morta (`docker kill`) 3 s
após o início da rajada — janela calibrada para haver bastante coisa em voo (a rajada inteira
levou 12 s neste ponto).

| Cenário | Recusados | Tipo |
|---|---:|---|
| Réplica única morta durante a rajada ([025](../wayfinder/tickets/025-carga-conservacao.md), herdado) | **361/400 (90,25%)** | 239 timeouts, 53 EOF, 46 resets, 22 recusas de conexão |
| Uma de três réplicas morta durante a rajada (aqui) | **39/400 (9,75%)** | **39 HTTP 502, zero timeout/EOF/reset** |

Os outros cinco portões passaram: dos 361 aceitos, todos foram a `CONCLUIDO` (nenhum
`FALHOU`, nenhum preso, frames certos, amostra pela API ok) — a perda é inteira na aceitação,
nada escapa depois dela.

### Por que não é zero, e por que não é a fila que falta

A causa não é falta de instrumento nem de tempo de espera — é uma regra deliberada do nginx.
Pelo log do proxy, os 39 seguem o mesmo padrão:

```
upstream prematurely closed connection while reading response header from upstream
upstream server temporarily disabled while reading response header from upstream
"POST /videos HTTP/1.1" 502
```

O nginx **conectou** na réplica que ia morrer, **enviou** o corpo do `POST` (a bufferização
default garante que o corpo inteiro já estava em mãos do proxy antes do envio — não é isso que
falha), e estava **esperando o cabeçalho da resposta** quando o `docker kill` fechou a conexão.
Nesse ponto, `proxy_next_upstream` **não tenta a próxima réplica por padrão** para um método
não-idempotente como `POST` — a razão documentada é correção, não limitação: a réplica morta
pode já ter terminado de processar (linha gravada no Postgres, ZIP no bucket) e só não
respondido a tempo, e reenviar o mesmo `POST` para outra réplica arriscaria criar um **segundo**
Vídeo para o mesmo envio do cliente. O parâmetro `non_idempotent` destravaria o retry — **não
foi ligado aqui**: fecharia a lacuna às custas desse risco de duplicação, e o endpoint
`/videos` não tem hoje nenhuma chave de idempotência que o absorva. Fica registrado como
candidato de melhoria não implementado, no mesmo espírito do 025 em relação ao 027 — este
ticket mede, não conserta.

**A troca de regime é a resposta real.** Com réplica única, a queda derruba a única porta de
entrada e o cliente vê o que a rede vê primeiro (timeout, EOF, reset, recusa — em qualquer
ordem, dependendo de em que fase da conexão cada envio estava). Com N réplicas atrás de um
proxy que sabe balancear, o mesmo evento vira uma janela estreita e bem definida — só quem
estava com uma conexão já aberta e em voo para a réplica específica que morreu, exatamente no
instante errado — e o efeito colateral (a resposta ao cliente) é sempre um `502` limpo e
imediato, nunca um timeout. É a diferença entre "a borda caiu" e "uma fração de um dígito
perdeu a corrida contra um `docker kill`".

---

## 6. Limitações desta medição

- **Uma corrida por ponto.** Ao contrário do 026 (duas repetições, mediana), aqui cada número
  vem de uma única rodada — mesma postura do 025, não do 026. A mediana de latência (5,5×) é
  grande o bastante para não ser ruído, mas o p95/máximo e a taxa de 9,75% do `mata-replica`
  não têm repetição para calibrar variância.
- **Máquina diferente das medições 026/027** (6 vCPU aqui contra 20 lá) — os números absolutos
  de vazão e latência não são comparáveis entre sessões; só as comparações internas (N=1 x N=3,
  réplica única x N réplicas) valem.
- **Não isolado onde o próximo teto está.** A pergunta original incluía "a partir de onde o
  gargalo deixa de ser a borda e passa a ser Postgres ou MinIO" — não medido: `extracao` já
  limita a vazão de dreno antes de qualquer um dos dois aparecer no horizonte, e não houve
  telemetria por serviço (ao estilo do `escalabilidade.sh`) nesta rodada.
- **Vazão de aceite em N alto não foi varrida.** Só N=1 e N=3 foram medidos; se o proxy em si
  vira gargalo em N maior (ele é single-threaded por worker, um único nginx) é desconhecido.
- **`non_idempotent` não foi medido**, só diagnosticado a partir do comportamento observado e
  da documentação do nginx — não há uma segunda rodada mostrando o número "com" para comparar
  contra os 39, nem uma verificação de linhas duplicadas no Postgres que uma rodada dessas
  exigiria.
