# Escala da borda

- id: 028
- label: wayfinder:task
- status: fechado
- assignee: vandrep (sessão de 2026-08-29)
- bloqueado-por: 027 (fechado)

## Question

A afirmação sob julgamento é a linha do `videos` na tabela *§ O que escala, e como* de
`docs/arquitetura.md`: **réplicas**, porque o estado está no Postgres e a transição é `UPDATE`
condicional. Aquela célula carrega hoje um **"Nunca medido"** e o número que o
[025](025-carga-conservacao.md) produziu sem querer: derrubar a réplica única durante uma
rajada custou **361 envios recusados de 400** — 239 timeouts de conexão, 53 EOF, 46 resets,
22 recusas.

Nasceu de um corte: o [026](026-linearidade-horizontal.md) julga a linearidade do `extracao` e
esta pergunta tentou entrar lá. É outro experimento — outro objeto (HTTP, não fila), outro
critério (recusa e latência, não vazão de drenagem) e construção nova de verdade. O valor do
corte 025/026 foi não deixar duas perguntas dividirem um veredito, e vale de novo aqui.

### Por que nasce bloqueado

O [027](027-melhorias-medidas.md) precisa fechar antes, e o motivo não é organização: o modo
`mata-videos` do harness, que é o instrumento óbvio para esta pergunta, hoje **mede os defeitos
1 e 2 do 025** em vez de medir a borda. Foi ele que produziu 34 Vídeos presos de 39 aceitos —
com o `.zip` no bucket e o usuário sem saber. Enquanto o evento terminal for descartado fora de
ordem e a marca do [ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) puder mentir,
qualquer coisa que este ticket medisse estaria contaminada por eles, e a conclusão seria sobre
os defeitos, não sobre a réplica.

### O que precisa ser construído

Ao contrário do 026, aqui não dá para reusar e varrer:

- **proxy no overlay de carga** (`docker-compose.carga.yml`), com `videos` em N réplicas atrás
  dele. O `docker-compose.yml` principal continua sendo a demo da banca e não recebe nada
  disto — mesma regra do 025;
- o injetor apontado para o proxy, e não para `videos:8080` direto;
- o critério: hoje o k6 mede latência do `202` e conta recusas, que é quase tudo o que esta
  pergunta pede. Falta o que acontece **durante** a queda de uma réplica de N.

### As duas perguntas, que são diferentes

1. **A borda escala?** N réplicas atrás do proxy aguentam mais envios simultâneos que uma —
   e a partir de onde o gargalo deixa de ser a borda e passa a ser Postgres ou MinIO.
2. **A borda sobrevive à queda de uma réplica?** Esta é a que interessa ao enunciado: com N>1,
   matar uma durante a rajada deveria custar **zero** requisição, porque as outras continuam
   aceitando. É a diferença entre os 361 recusados do 025 e o que o desenho promete.

A segunda vale mais que a primeira. A primeira é vazão de borda, que ninguém duvida; a segunda
é a única forma de perda que a fila não protege, e é o que o `arquitetura.md` afirma sem ter
visto.

### Critério, a fixar antes de rodar

Não aqui — mas com a mesma regra que os dois tickets anteriores seguiram: limiar declarado
**antes**, senão todo resultado vira narrativa pós-fato. E os portões de validade do 026 valem
(`quantidade_frames`, zero `FALHOU` com fixture válido, zero presos): a borda "aceitar" não é
o desfecho, é o começo dele.

### Saída

Números em `docs/pesquisa/` e a célula do `videos` na tabela § *O que escala, e como* perdendo
o **"Nunca medido"** — para ganhar um número, seja ele qual for. Se o resultado reprovar, ele
vai para § *Limitações conhecidas* com o número ao lado, que é o padrão que o 025 estabeleceu.


## Desbloqueado pelo 027

Os defeitos 1 e 2 que envenenavam o modo `mata-videos` estão corrigidos e remedidos (0/133
terminais com a borda derrubada), então a pergunta desta ficha — *matar uma réplica de N deveria
custar zero requisição* — já é medível. Três coisas que o 027 entrega prontas:

- **`FIAPX_ATRASO_KILL`** no harness: os 3 s fixos não alcançavam a janela, e sob 400 VUs
  simultâneos nada completa antes do kill. As rodadas úteis daqui saíram com `FIAPX_VUS=40`.
- **Portão de validade de rodada** no modo `mata-videos`: rodada com 0 aceitos agora sai com
  código 2 em vez de passar verde. Vale igual aqui, onde o alvo do kill também é a borda.
- **Aviso de instrumento**: nada no repo constrói as imagens que o Compose referencia
  (`ghcr.io/vandrep/fiapx-*:latest`). Construa antes de medir, ou meça o binário antigo em
  silêncio.

O número de linha de base continua o do 025: **361 recusados de 400** ao derrubar a réplica
única.

## Resolução

Números completos em
[`docs/pesquisa/carga-escala-borda.md`](../../pesquisa/carga-escala-borda.md). A célula do
`videos` na tabela § *O que escala, e como* perde o "Nunca medido".

**Pergunta 1 — a borda escala?** Sim, em latência de aceite: sob 400 conexões simultâneas, a
mediana do `202` caiu de **630 ms (N=1) para 114 ms (N=3)**, 5,5×. A vazão de dreno não mudou
(400 concluídos em 46s nos dois pontos) porque quem limita o dreno é o `extracao`, não o
`videos` — a réplica extra da borda alivia o aceite, não tem o que acelerar depois dele. Não
isolado onde o próximo teto está (Postgres, MinIO ou o próprio `extracao`) nem se o proxy em si
vira gargalo em N maior que 3 — fica para o "Ainda não especificado" do mapa se algum dia
importar.

**Pergunta 2 — matar uma réplica de N custa zero requisição?** Quase, não zero: **39
recusados de 400 (9,75%)**, contra os 361/400 (90,25%) da réplica única — 9,3× menos perda.
Os 39 são todos `502` (zero timeout, EOF ou conexão recusada), e a causa é uma regra
deliberada do nginx: `proxy_next_upstream` não reencaminha um `POST` (não-idempotente) para
outra réplica depois que a conexão com a réplica morta já falhou esperando resposta — porque
ela pode já ter completado o efeito colateral (linha gravada, ZIP no bucket) antes de morrer, e
reencaminhar arriscaria duplicar o Vídeo. O parâmetro `non_idempotent` fecharia a lacuna às
custas desse risco; **não foi ligado** — o endpoint `/videos` não tem hoje chave de
idempotência que absorva um retry duplicado, e este ticket mede, não conserta (mesma fronteira
que o 025 traçou para o 027). Fica registrado como candidato de melhoria não implementado.

**O que precisou ser construído, e não estava previsto.** O overlay de carga ganhou um proxy
L7 (`videos-proxy`, nginx) na frente de N réplicas do `videos` — só existe ali, a demo da
banca continua com uma réplica e sem proxy. O `nginx.conf` é gerado em **runtime** pelo
entrypoint do container, porque o número de réplicas e o hostname de cada uma
(`<projeto>-videos-<índice>`, verificado por teste direto) só existem em runtime; um upstream
estático nunca teria failover. A bufferização de corpo do `POST` ficou no **default (ligada)**
de propósito — é o que permite ao nginx reencaminhar o corpo inteiro para a próxima réplica
quando a atual morre no meio, e é exatamente essa propriedade que faz a pergunta 2 ter uma
resposta diferente de "zero" e de "tudo".

**Achado de instrumento, não de sistema.** Esta sessão roda em Docker-outside-of-Docker — o
`dockerd` real fica fora do container onde o harness executa, e os dois só concordam no
conteúdo de `/workspace`, não no caminho. Bind mount por caminho relativo precisou de
`--project-directory` apontando para o caminho real do host (exposto em
`LOCAL_WORKSPACE_FOLDER` pelo devcontainer), e a checagem "amostra pela API" (que batia em
`localhost:8080`/`8081`, inalcançável de dentro do devcontainer) passou a rodar dentro de um
container na rede do Compose, contra `videos-proxy:8080`/`keycloak:8080` — mesmo truque que o
injetor já usava. Nenhuma das duas mexe em `oraculo.sh`, que continua certo para quem roda no
mesmo host do `dockerd`; ambas vivem só em `scripts/carga/borda.sh`. Achado menor dentro do
proxy: o healthcheck usava `http://localhost:8080`, e a imagem `nginx:alpine` resolve
`localhost` para `::1` primeiro — o nginx aqui só escuta IPv4, e o healthcheck ficava
"unhealthy" para sempre com o proxy respondendo normal em `127.0.0.1`. Trocado para
`127.0.0.1` explícito.

**Máquina diferente das medições 025-027** (6 vCPU nesta sessão contra 20 nas anteriores) — os
números absolutos de vazão não são comparáveis entre sessões; só as comparações internas
(N=1 × N=3, réplica única × N réplicas) valem, e são elas que respondem as duas perguntas.

**Uma corrida por ponto**, não repetida — mesma postura do 025, não do 026. A mediana de
latência (5,5×) é grande o bastante para não ser ruído; o p95/máximo e a taxa de 9,75% do
`mata-replica` não têm repetição para calibrar variância.
