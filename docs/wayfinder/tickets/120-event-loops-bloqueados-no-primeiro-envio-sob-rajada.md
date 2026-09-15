# Event loops do `videos` bloqueados no começo de uma rajada

- id: 120
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Critérios fixados por escrito antes das corridas.
- [ ] As duas corridas (a frio e aquecida) registradas com comando, imagem, contagem e duração dos
      avisos do `BlockedThreadChecker`, e mediana e p95 do `202`.
- [ ] Qual das hipóteses a medição sustenta, ou que nenhuma das duas sustenta.
- [ ] Se houver mudança de código: aprovada pelo mantenedor antes, e remedida com zero avisos do
      `BlockedThreadChecker` na rajada a frio e os seis critérios do `borda.sh` verdes.
- [ ] Linha em "Decisões até aqui" no mapa.
