# Event loops do `videos` bloqueados no começo de uma rajada

- id: 120
- label: ready-for-agent
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 14b1f0d)
- bloqueado-por:
- prioridade: P3

## Origem

Observado na rodada do [ticket 114](114-proxy-de-carga-aguenta-a-rajada-padrao.md), em
2026-09-15, sobre `develop @ dabb22a`, com a imagem `videos` `17ce23bc1408`. O mantenedor pediu
que se abrisse este ticket.

## O problema

Na rodada `ticket114-escala-n1` (`borda.sh escala 1`, 400 envios, 400 conexões), o `videos`
terminou o boot às 08:51:34 UTC, e a rajada começou poucos segundos depois. Entre 08:51:43 e
08:51:45, o `BlockedThreadChecker` do Vert.x acusou **8 event loops bloqueados**, de 2,0 s a
4,3 s. Depois disso não houve mais nenhum aviso, e a rajada ainda seguiu por uns 15 s. A mediana
do `202` foi de 14,0 s, contra 6,2 s na rodada A2 do 108, com 200 VUs. A rodada passou nos seis
critérios, então o problema é de latência e não de correção.

Os oito stack traces passam pelo mesmo caminho:
`VideosResource.enviar` → `EnviarVideoUseCase.executar` → `ArquivoMinioAdapter.gravarVideo` →
`ArquivoMinioClient.gravar` → `S3AsyncClient.putObject`, **no event loop**. Onde cada um parou:

| Traces | Parado em |
|---|---|
| 2 (08:51:43) | `ReentrantLock.lock` dentro de `ApplicationScoped_ContextInstances.computeIfAbsent`, a partir do `S3AsyncClient_ClientProxy.arc$delegate`. É a criação preguiçosa do bean `S3AsyncClient` no primeiro uso, com os outros event loops esperando o lock |
| 6 (08:51:45) | assinatura SigV4 do SDK (`AwsChunkedV4PayloadSigner`, `V4CanonicalRequest`, `SignerUtils.moveContentLength`), três deles carregando classe do jar (`RunnerClassLoader.loadClass` → `Crc32Checksum.<init>`) |

O que isso sugere, **sem ter sido medido**: o primeiro `putObject` depois do boot paga no event
loop a criação do cliente S3, o carregamento das classes da assinatura e do checksum, e o
JIT frio. Sob rajada, todos os event loops pagam esse preço ao mesmo tempo. A outra explicação,
que não está descartada, é CPU disputada: a assinatura faz trabalho de CPU no event loop, e o host
de 8 núcleos roda o `extracao` junto. Os avisos pararem depois de 2 s favorece a partida a frio,
mas não prova.

A evidência é local. O log completo do `videos` da rodada ficou em
`scripts/carga/saida/ticket114-escala-n1/videos.log`, fora do git. Uma nova corrida do harness
apaga esse diretório só se usar o mesmo rótulo.

Relação com as regras do repositório: o `AGENTS.md` protege "nada de retorno bloqueante na borda".
Aqui o bloqueio não vem de código do projeto, e sim de inicialização e CPU do SDK dentro de uma
cadeia assíncrona. O teste arquitetural não enxerga isso, e só aparece sob rajada.

## O que fazer

1. **Reproduzir e separar as duas hipóteses**, com critérios fixados por escrito antes de rodar:
   - rajada logo depois do boot, como no 114;
   - a mesma rajada com o `videos` aquecido, depois de alguns envios isolados e uma pausa, sobre o
     mesmo `down -v`.
   Para cada uma, registre a contagem e a duração dos avisos do `BlockedThreadChecker` e a mediana e
   o p95 do `202`. O `scripts/carga/borda.sh` sobe a stack e já dispara; o aquecimento pode entrar
   por variável do harness ou por uma corrida manual documentada.
2. **Se a partida a frio se confirmar**, proponha ao mantenedor como antecipar a inicialização
   antes de implementar. Por exemplo: forçar a criação do `S3AsyncClient` no `StartupEvent`, ou
   fazer uma chamada barata ao MinIO no boot, antes de o readiness ficar verde. Diga o que cada
   opção cobre dos dois grupos de traces: o lock do bean e a assinatura com carga de classe.
3. **Se a CPU disputada se confirmar**, registre a medição e proponha ao mantenedor se a
   assinatura sai do event loop ou se o limite fica declarado. Não mude o caminho do upload sem
   essa decisão: ele carrega a repetição do ADR 0001 e o contexto de chamada do
   `ArquivoMinioAdapter`.

Se a corrida passar de 10 min, rode sob `systemd-inhibit`.

## Critérios de aceite

- [x] Critérios fixados por escrito antes das corridas.
- [x] As duas corridas (a frio e aquecida) registradas com comando, imagem, contagem e duração dos
      avisos do `BlockedThreadChecker`, e mediana e p95 do `202`.
- [x] Qual das hipóteses a medição sustenta, ou que nenhuma das duas sustenta.
- [x] Não houve mudança de código de produção: a única mudança foi no harness, para tornar o
      aquecimento e o namespace Docker reproduzíveis; portanto não foi aplicada correção sem
      aprovação do mantenedor nem há uma re-medida de correção a declarar.
- [x] Linha em "Decisões até aqui" no mapa.

## Critérios, fixados antes de rodar

Escritos em 2026-09-15, antes das duas corridas, sobre `develop @ 14b1f0d`. O instrumento foi
`scripts/carga/borda.sh escala 1 400`, com o fixture padrão `controle-3s.mp4`, 400 VUs, duas
réplicas de `extracao` e uma réplica de `videos` atrás do proxy. A stack seria derrubada com
`down -v` antes de cada corrida; para não tocar numa stack padrão possivelmente existente, o
projeto Compose foi isolado pelo namespace opt-in `FIAPX_PROJETO_COMPOSE=fiapx-ticket120`.

Na rodada fria não haveria envio antes da rajada. Na rodada aquecida, o mesmo harness faria cinco
envios sequenciais (`FIAPX_AQUECER=5`, `VUS=1`), esperaria 10 s
(`FIAPX_PAUSA_AQUECIMENTO=10`) e só então iniciaria a mesma rajada medida. O aquecimento ficaria
fora do denominador dos 400 ids. Em cada rodada seriam registrados: todos os avisos do
`BlockedThreadChecker` e suas durações, a mediana e o p95 do `202`, o digest das imagens e os
seis critérios funcionais do `borda.sh`.

## Resolução

Implementado em 2026-09-15 sobre `develop @ 14b1f0d`, sem alteração do caminho de produção.

O `scripts/carga/borda.sh` ganhou opções de experimento: `FIAPX_PROJETO_COMPOSE` para isolar o
projeto Docker da corrida, e `FIAPX_AQUECER`/`FIAPX_PAUSA_AQUECIMENTO` para fazer
envios sequenciais e uma pausa antes da rajada. A sintaxe do shell e o diff passaram antes da
rodada; o harness também captura automaticamente o `videos.log` e as durações dos avisos. As
saídas completas ficam nos diretórios ignorados
`scripts/carga/saida/ticket120-fria-v2/` e `scripts/carga/saida/ticket120-aquecida-v2/`.

### Comandos e imagem

```text
FIAPX_PROJETO_COMPOSE=fiapx-ticket120 FIAPX_ROTULO=ticket120-fria-v2 \
FIAPX_EXTRACAO_REPLICAS=2 FIAPX_EXTRACAO_CPUS=1 \
scripts/carga/borda.sh escala 1 400

FIAPX_PROJETO_COMPOSE=fiapx-ticket120 FIAPX_ROTULO=ticket120-aquecida-v2 \
FIAPX_AQUECER=5 FIAPX_PAUSA_AQUECIMENTO=10 \
FIAPX_EXTRACAO_REPLICAS=2 FIAPX_EXTRACAO_CPUS=1 \
scripts/carga/borda.sh escala 1 400
```

As duas rodadas usaram `ghcr.io/vandrep/fiapx-videos:latest`, digest local
`sha256:534c1295d0b72a6ac0d44ea944dc9d0e787f067dc590cd135dce982efc57478f`, e
`ghcr.io/vandrep/fiapx-extracao:latest`, digest local
`sha256:f487b93f859e911814a5d99647ab25887f1dd042e785bc6fdd5e1b6770493f9f`.

### Medição

Os horários abaixo são do log do container, no fuso local da sessão (`America/Sao_Paulo`).

| Rodada | Preparo | `BlockedThreadChecker` | `202` mediana | `202` p95 | Resultado funcional |
|---|---|---:|---:|---:|---|
| Fria | boot saudável às 12:23:04, rajada sem aquecimento | **4**: 2.323, 2.023, 3.531 e 4.351 ms; janela 12:23:28.821–12:23:30.807 | **25.310 ms** | **28.735 ms** | 400/400 terminais, 0 recusas, 0 presos, 0 `FALHOU`, frames corretos |
| Aquecida | 5 envios sequenciais, pausa de 10 s; boot saudável às 12:27:00 | **0** | **1.101 ms** | **3.020 ms** | 400/400 terminais, 0 recusas, 0 presos, 0 `FALHOU`, frames corretos |

O `max` do `202` foi 29.843 ms na fria e 4.273 ms na aquecida. O aquecimento produziu cinco
Vídeos adicionais, por isso a listagem final informou 405, mas os 400 ids da rajada medida foram
os únicos usados nos portões. A rodada fria e a aquecida passaram nos seis critérios do
`borda.sh`.

O primeiro aviso frio ocorreu cerca de 24 s depois do `started` do Quarkus e os avisos cessaram
após a inicialização. Os quatro stacks atravessam o synthetic bean do `S3AsyncClient`, o builder
do SDK e `putObject`; dois incluem `MetadataLoader`/`RunnerClassLoader`, e dois ficam em
`ReentrantLock`/`ApplicationScoped_ContextInstances.computeIfAbsent`. A rodada aquecida não
contém esses stacks nem qualquer aviso do `BlockedThreadChecker`.

### Leitura e proposta ao mantenedor

A medição sustenta **partida a frio do cliente S3/SDK** como causa dos bloqueios observados. A
hipótese de CPU disputada não é a explicação dominante neste ensaio: host, imagem, cota, número
de réplicas e rajada foram mantidos, e só a inicialização prévia mudou; ainda assim, esta sessão
não quantifica o custo de CPU da assinatura depois de aquecida. Também não há suporte para
handshake de rede como causa primária, porque os stacks observados são de criação do bean e
carga de classes, não de conexão Netty.

Há duas opções para decisão antes de qualquer correção de produção:

1. **Forçar a criação do `S3AsyncClient` num `StartupEvent`, antes do readiness.** Uma chamada
   local ao bean cobre o lock de `ApplicationScoped_ContextInstances` e o builder/carga de
   classes mostrados nos stacks frios. Sozinha, ela não garante que as classes específicas do
   `putObject` — assinatura em corpo particionado, checksum e JIT — tenham sido executadas.
2. **Fazer uma chamada barata ao MinIO no boot, antes do readiness.** Um `headBucket` ou
   `headObject` cobre a criação do bean, a conexão e a assinatura de uma requisição real; não
   necessariamente cobre `AwsChunkedV4PayloadSigner` e checksum do upload. Um `putObject`
   mínimo num objeto sentinela cobriria o caminho exato, mas acrescentaria ciclo de vida e
   limpeza de objeto ao boot.

Recomendação para a decisão: começar pela opção 1 e reexecutar a rodada fria; se ainda houver
classes do caminho de upload nos stacks, escolher entre uma preflight S3 explícita ou o sentinela
da opção 2. Não mover a assinatura para outra thread nem mudar a cadeia do
`ArquivoMinioAdapter` sem essa decisão: a repetição do ADR 0001 e a ponte de contexto do upload
continuam intactas. Não existe teste unitário que reproduza a corrida entre CDI lazy, carga do
SDK e vários event loops; a regressão deve continuar sendo julgada pelo harness real.
