# Melhorias justificadas pela medição

- id: 027
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Question

**O que a medição condenar, e nada além.**

Este ticket nasce bloqueado e com o corpo vazio de propósito. É a defesa contra o vício
clássico de teste de performance: decidir a melhoria antes de o número chegar, e depois
procurar o número que a justifique. As candidatas óbvias — segundo consumidor no `extracao`,
`prefetch` maior, pool do Postgres, intervalo da varredura de reconciliação, `max-body-size`,
paralelismo do `ffmpeg` — **não entram aqui até que [025](025-carga-conservacao.md) ou
[026](026-linearidade-horizontal.md) apontem uma delas com um número**.

O que preenche este ticket, quando os dois fecharem:

- as melhorias **aceitas**, cada uma com a medição que a justificou e a medição de depois;
- as melhorias **recusadas**, cada uma com o número ao lado — mesmo padrão da tabela *"O que
  foi recusado, e por quê"* de `docs/arquitetura.md`, que é o que impede a recusa de virar
  esquecimento.

Se alguma escolha aqui for irreversível e envolver alternativas reais, ela ganha **ADR**. As
decisões de método de medição dos dois tickets anteriores não ganharam, e por isso: são
reversíveis e baratas.

Se a medição não condenar nada, este ticket fecha **sem mudança de código** e com essa frase na
resolução. Isso é um resultado, não um fracasso.

## Condenados pelo 025

Nenhuma das candidatas óbvias listadas acima apareceu. O 025 condenou **três defeitos de
correção**, não de vazão — e o ticket segue bloqueado pelo 026, porque só ele pode acrescentar
os de vazão. Cada um com o número que o condena:

1. **Evento terminal descartado quando chega fora de ordem.** `ExtracaoIniciada` e
   `ExtracaoConcluida` vêm em filas independentes e sem ordem entre si; a `Concluida` que chega
   primeiro não casa o predecessor `PROCESSANDO`, altera zero linhas e recebe ack. Vídeo preso
   em `PROCESSANDO` para sempre, **com o `.zip` já no bucket** — os censos somam 46 presos, e
   os 45 que a conferência contra o MinIO cobriu tinham todos o `.zip` lá. Incidência: 11/400
   (2,75%) sob pico com uma réplica reiniciada, **34/39** depois de o
   `videos` cair e voltar. É a perda de requisição que o enunciado proíbe, e nenhuma varredura
   existente a alcança.
2. **A marca do ADR 0003 pode mentir.** `publish-confirms` é `false` por default no conector
   (verificado no `@ConnectorAttribute` do smallrye-reactive-messaging-rabbitmq 4.32.1), então
   o envio completa antes de o broker confirmar. Medido: 3 Vídeos em `RECEBIDO` com
   `comando_publicado_em` preenchido e comando nenhum no broker — e como a varredura filtra por
   marca nula, ela nunca os reconsidera.
3. **A varredura de órfãos no boot do `extracao` apaga o scratch das réplicas vivas.** O volume
   nomeado é compartilhado por todas as réplicas, e o Javadoc de `limparOrfaosNoBoot` assume
   exclusividade. Medido: duas réplicas com `Error submitting a packet to the muxer: No such
   file or directory` no instante do boot de uma terceira, e um h264 válido entregue ao usuário
   como `ARQUIVO_INVALIDO`.

**Condição de aceite do 2, e ela não é opcional.** A varredura do ADR 0003 nunca foi observada
republicando: a rodada `mata-videos` do 025 existia para isso e o que produziu foi este defeito.
Enquanto 1 e 2 existirem, o cenário que a exercitaria está envenenado por eles — a demonstração
só é possível depois da correção. Logo, o 2 só fecha com uma rodada `mata-videos` que mostre a
varredura republicando de fato; sem ela, o `docs/arquitetura.md` continua com a ressalva de
"afirmada e não verificada" na linha da tabela.

O 1 e o 2 são candidatos a **ADR**: mexem em garantia declarada e as alternativas são reais
(reordenar no consumidor, tolerar a chegada fora de ordem no `UPDATE`, ligar `publish-confirms`,
ou estender a varredura para Vídeos parados). O 3 é local e provavelmente não paga ADR.


## Condenados pelo 026

O 026 **confirmou** a linearidade (eficiência 0,88 em 6 réplicas contra critério de 0,80), então
não condenou o desenho. Condenou **um** ponto, e é o primeiro candidato de vazão deste ticket —
todos os do 025 são de correção.

4. **O `ffmpeg` abre 20 threads contra uma cota de 2 CPUs.** `nproc` dentro do container devolve
   os 20 núcleos do host, não a cota do cgroup, então o `ffmpeg` default dimensiona o pool pelo
   número errado e as 20 threads são estranguladas em bloco pelo CFS. Medido no mesmo fixture de
   2 min: **20,84 s com o default, 14,14 s com `-threads 2` — 32%**, e o valor com `-threads 2`
   bate quase exato com 2 núcleos dedicados via `taskset` (14,02 s), o que fecha o diagnóstico: a
   perda inteira era sobre-assinatura contra a cota.

   É também a hipótese mais provável para os 12% de perda de eficiência em `N=6`, onde há ~120
   threads de `ffmpeg` disputando 20 núcleos sob seis cotas independentes — a CPU por réplica cai
   de ~196% para ~170% conforme `N` cresce, isto é, cada réplica deixa de conseguir gastar a
   própria cota. Essa parte **não foi testada na varredura**, só isoladamente.

   O que torna isto não-trivial e provavelmente merecedor de discussão: o número de threads certo
   **depende da cota**, que é config de implantação, não de código. Fixar `-threads 2` no adapter
   acopla o serviço a um `cpus=2` que só existe no overlay de carga; o `docker-compose.yml` da
   demo não põe teto nenhum, e ali o default de 20 threads é o **certo** (3,04 s medidos sem
   teto). As alternativas reais são ler a cota do cgroup em runtime, expor um
   `fiapx.extracao.threads` configurável, ou não mexer e documentar. Provavelmente **não** é ADR
   — é reversível —, mas é decisão, não digitação.

**Nota de método herdada do 026, que vale para as medições de "depois" deste ticket.** O harness
ganhou um sexto portão de validade — continuidade da série de telemetria — porque duas das doze
corridas do 026 foram corrompidas pelo *host suspendendo* no meio, o que estica o denominador sem
falhar nenhum dos outros cinco critérios. Toda medição de antes/depois aqui roda sob
`systemd-inhibit --what=sleep:idle`, ou repete o mesmo erro.

O 3 ganha contexto novo: o 026 o **contornou por protocolo** (nada boota com trabalho em voo) e
percorreu 12 corridas com até 6 réplicas sem incidência — o que confirma que é defeito de boot, e
não de regime, mas não o corrige.


## Resolução

**Quatro aceitas, todas com o número de antes e o de depois. Nenhuma recusada por preço — o que
a medição condenou, a medição também mediu de volta.** Todas as corridas sob
`systemd-inhibit --what=sleep:idle`, pelo sexto portão do 026.

| Defeito | Antes | Depois |
|---|---|---|
| 1. Terminal fora de ordem descartada (pico, réplica reiniciada) | 11/400 presos em `PROCESSANDO` | **0/400**, 400 concluídos em 93 s |
| 1. Idem, com o `videos` derrubado | 34/39 presos | **0/133** terminais em 39 s |
| 2. Varredura nunca observada republicando | nenhum registro, em rodada nenhuma | `reconciliacao republicou 1 comando(s) e 0 falha(s)`, órfão a `CONCLUIDO` |
| 4. `ffmpeg` sobre-assinando a cota | 18,54 s (mediana de 3) no fixture de 2 min sob `cpus=2` | **12,68 s**, −31,6% |

### 1. O predecessor virou conjunto (ADR 0002 emendado)

`CONCLUIDO` e `FALHOU` passam a aceitar `RECEBIDO` **ou** `PROCESSANDO`; `EstadoVideo.predecessores()`
devolve `Set` e o `WHERE` virou `estado in ?`. Foi a alternativa "tolerar a chegada fora de ordem
no `UPDATE`", contra reordenar no consumidor (exige estado e timeout) e fila única (quebra o
contrato do 007). O argumento que decide: **`PROCESSANDO` é informação de acompanhamento, não
portão** — pular o aviso de "alguém pegou" não invalida o desfecho, e o desfecho estava no bucket.

A guarda de unicidade do e-mail não afrouxou, e isso não é asserção: o `UPDATE` continua mudando
a linha exatamente uma vez, e há teste saindo de `RECEBIDO` que exige um `VideoFalhou` só.

**Um teste do repositório afirmava o defeito.** `VideoTest.concluirSemPassarPorProcessandoNaoMudaNada`
exigia que concluir a partir de `RECEBIDO` não mudasse nada — era a regra errada, escrita com
confiança. Foi invertido, com o comentário dizendo que já afirmou o contrário.

### 2. `publish-confirms=true`, e a varredura deixou de ser muda

A marca mentia porque o default do conector é `false`. Ligado nos dois canais de saída do `videos`.

Mas a condição de aceite do ticket não era essa — era **ver a varredura republicando**, o que
nunca acontecera. Descoberto o porquê: `ReconciliarPublicacoesPendentesUseCase` era **silenciosa**,
então nem a rodada do 025 nem duas rodadas `mata-videos` daqui poderiam tê-la flagrado, mesmo que
ela tivesse agido. O use case passou a devolver quanto republicou e o `@Scheduled` registra só as
passadas não vazias — a passada vazia é o caso normal a cada 30 s e afogaria o log.

Com isso a observação saiu, e é honesto dizer **como**: nas rodadas `mata-videos` a queda **não
produziu órfão nenhum** (134 linhas, 134 com marca — a janela é estreita demais para o
`docker kill` acertar de propósito). A demonstração foi feita contra um órfão **semeado** — linha
em `RECEBIDO`, marca nula, `recebido_em` 5 min atrás —, que a varredura republicou e levou a
`CONCLUIDO`. Isso exercita o mecanismo do ADR 0003 de ponta a ponta; não exercita a corrida que o
cria. A distinção está registrada no ADR.

**O custo que eu previ não existe.** Argumentei que confirms custaria um round-trip por
publicação. Medido com as duas imagens sob a mesma rajada de 400: mediana do `202` em 11233 ms
com confirms contra 11504 ms sem, p95 14864 contra 15647, drenagem 105 s nos dois. A diferença
está no ruído, e o sinal de que ela estaria: a latência do `202` ali é fila na borda, não broker.

### 3. Gate por idade na varredura de órfãos

`limparOrfaosNoBoot` só apaga diretório ocioso há mais de uma hora (folga larga sobre o teto de
20 min do 011 e o timeout de 300 s), e a idade vem do arquivo mais recente **em qualquer
profundidade** — o `ffmpeg` grava os frames dentro do diretório e nem todo sistema de arquivos
toca o mtime do pai. Não conseguir datar conta como "em uso", conservador.

Recusadas com o motivo ao lado, porque trocavam um defeito por outro: **subdiretório por réplica**
(container reiniciado nasce com hostname novo, o subtree antigo nunca mais é varrido — troca
sabotagem por vazamento) e **volume anônimo por réplica** (vaza volume a cada recriação e some o
teto de 4 GB do 011).

### 4. `-threads` derivado da cota, e não configurável

O dilema que o ticket registrava — "o número certo depende da cota, que é config de implantação"
— **se dissolveu num fato**: a JVM já lê a cota. `-XshowSettings:system` sob `cpus=2` num host de
20 núcleos imprime `Effective CPU Count: 2` enquanto `nproc` responde 20, e é esse número que
`availableProcessors()` devolve. Então `-threads availableProcessors()` é certo nos dois mundos,
e sem teto reproduz o default de sempre.

Verificado **no processo vivo**, não só no raciocínio: durante a rodada de carga, a linha de
comando do `ffmpeg` dentro do container era `... -loglevel level+repeat+error -threads 2 -xerror ...`.

**Recusada** a propriedade `fiapx.extracao.threads`, e o motivo vale a linha: um número que a JVM
já sabe ler viraria conhecimento tribal num `.env`, com a falha silenciosa de ficar
desatualizado quando a cota mudasse. Sem ADR — é reversível e o valor não é escolha, é leitura do
ambiente.

## O que apareceu por medir, e não estava no ticket

- **O harness dava falso verde.** O modo `mata-videos` passou **duas vezes com 0 aceitos**: a
  borda caía antes do primeiro `202` e os critérios 2 a 5 se satisfaziam com 0/0. Ganhou um
  **portão de validade de rodada** (critério 0: ao menos um `202` antes da queda, senão a rodada
  é inválida e sai com código 2) e `FIAPX_ATRASO_KILL`, porque os 3 s fixos não alcançavam a
  janela. Mesma família do sexto portão do 026: o instrumento reprovando a si mesmo.
- **O harness mede a imagem que estiver por perto.** `docker-compose.yml` referencia
  `ghcr.io/vandrep/fiapx-*:latest` e nenhum script constrói — rodar o harness depois de mexer no
  código mede o binário de três dias atrás, em silêncio. As imagens foram construídas à mão aqui,
  com o estado anterior guardado em `:pre-027`, que é o que tornou possível a comparação de custo
  do confirms.
- **O `IN` não tinha teste.** `estado in ?` com `Set` de enum é HQL que nenhum teste do `core`
  alcança e que o BDD também não — ele monta `CONCLUIDO` atribuindo a entidade direto. Sem
  cobertura, a correção do defeito 1 só seria exercitada em produção.
  `VideoDataSourceAdapterTest` fechou isso (5 testes contra Postgres de verdade) e exigiu
  `quarkus-test-vertx`: `Panache.withTransaction` do thread do JUnit morre com
  "No current Vertx context found".
- **"Could not load class" no `videos` é o Keycloak, não o teste.** Quando o Dev Services for
  Keycloak estoura o timeout do Testcontainers, a augmentação do Quarkus falha e o JUnit reporta
  `ClassSelector ... resolution failed` / "Could not load class with name: ...", que parece erro
  de compilação e manda procurar no lugar errado — a causa está dezenas de linhas acima, num
  `Timed out waiting for log output matching '.*Keycloak.*started.*'`. Nesta máquina isso é
  intermitente e depende de carga: o Keycloak reconstrói a própria imagem no boot (25,7 s só de
  augmentação) e passa raspando no limite. Rodadas do `verify` alternaram entre verde e essa
  falha sem nenhuma mudança de código; a contagem de 144 saiu de duas rodadas verdes.

**144 testes verdes (103 sem Docker)**, contra 130 (96) antes — medido, não somado: 95 no
`videos`, 27 no `extracao`, 22 no `notificacao`, com 41 dependentes de Docker (33 de Cucumber,
5 do adapter novo, 2 do scratch, 1 do retry). A soma das linhas por ticket continua devendo
duas unidades ao total real, exatamente a divergência que o ticket 023 já registrara.
