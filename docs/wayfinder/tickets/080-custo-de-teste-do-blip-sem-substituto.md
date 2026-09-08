# O custo de teste que o 048 comprou voltou sem substituto

- id: 080
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P3

## Origem

Achado dos dois eixos da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 064](064-chaves-orfas-de-fault-tolerance.md) tirou do `application.properties` do
`videos` as duas chaves que zeravam o `delay` do `@Retry` no perfil de teste. Elas eram órfãs de
um interceptor que já tinha saído, e tirá-las estava certo. O que saiu junto foi o efeito que o
[048](048-retry-no-acesso-ao-minio-pela-borda.md) tinha comprado, e o comentário removido dizia qual:

> com os 2s de produção, um envio contra armazenamento persistentemente fora custaria 6s parados
> por cenário.

Hoje a repetição vive em `comRepeticao`, no `ArquivoMinioClient`:
`ESPERA_ENTRE_REPETICOES` é `Duration.ofSeconds(2)`, constante `private static final`, e
`withBackOff` a usa como mínimo e máximo. Não há perfil que a altere. O
`EnvioResisteABlipDoArmazenamentoTest` não mudou no intervalo, então o cenário voltou a pagar a
espera real.

O 064 previu exatamente este caso e pediu registro:

> se algum cenário depender do `delay` zerado, a dependência era do interceptor que já saiu, e o
> ticket registra o que a passou a substituir.

A `## Resolução` dele diz apenas "Contagem e espera seguem os valores do codigo". Isso descreve
o estado, não registra a troca: o que o 048 tinha comprado deixou de existir e ninguém decidiu
pagar de novo.

## O que entregar

Uma das duas, decidida e escrita:

- A espera volta a ser ajustável por perfil de teste — configuração no `ArquivoMinioClient`, não
  chave de interceptor —, e o cenário do blip volta a custar o que custava. O que está sob teste
  continua sendo a repetição acontecer, não quanto ela espera; foi essa a leitura do 048.
- Ou o custo fica, medido e registrado neste ticket e no Javadoc do teste, como escolha de manter
  a espera de produção sob teste.

Em qualquer dos dois casos, o Javadoc do `EnvioResisteABlipDoArmazenamentoTest` descreve a
proteção que existe hoje e o que o cenário custa.

## Critérios de aceite

- [x] O custo atual do cenário do blip está medido e escrito
- [x] A escolha entre ajustar e pagar está registrada, com o motivo
- [x] Se a espera virar configurável, nenhuma chave de tolerância a falhas por interceptor volta
      ao `.properties` — a guarda do 064 continua verde
- [ ] `./mvnw test` verde a partir da raiz — **não satisfeito**, por ausência de
      `ffmpeg`/`ffprobe` no host e não por este ticket; ver a `## Resolução`

## Resolução

**Escolhida a primeira das duas opções: a espera voltou a ser ajustável por perfil de teste**, e
o cenário do blip voltou a custar o que custava antes do [064](064-chaves-orfas-de-fault-tolerance.md).

**O custo, medido antes de decidir.** `EnvioResisteABlipDoArmazenamentoTest`, quatro cenários, na
mesma máquina:

| | Tempo da classe |
|---|---|
| Espera de produção (2 s), como estava | **26,46 s** |
| Espera de teste (1 ms) | **6,17 s** |

**20,3 s parados**, e o número não é surpresa: é exatamente a soma prevista pela aritmética da
política — 3 repetições × 2 s nos dois cenários de armazenamento persistentemente fora (6 s cada,
o número que o comentário removido pelo 064 já dizia) e 2 repetições × 2 s nos dois cenários de
blip (4 s cada). O trabalho real da classe são os ~6 s restantes.

**Por que ajustar e não pagar.** O que está sob teste é a repetição *acontecer* e o desfecho que
ela produz — 202 quando o armazenamento volta, 500 quando não volta —, não a duração da espera.
Foi essa a leitura do [048](048-retry-no-acesso-ao-minio-pela-borda.md) quando ele comprou o mesmo
efeito, e nada no intervalo a contradisse. Pagar 20 s por cenário para reafirmar uma constante que
o default do `@ConfigProperty` já guarda é o pior dos dois negócios.

**Como, sem reabrir o buraco que o 064 fechou.** A espera virou
`fiapx.armazenamento.espera-entre-repeticoes`, injetada com `@ConfigProperty` no
`ArquivoMinioClient` e com **default de 2 s no próprio código** — não no `.properties`, para que a
espera de produção sobreviva a um arquivo de configuração incompleto. O `%test` do `videos` a
baixa. É configuração **deste bean**, no namespace `fiapx.` do projeto, e não chave de tolerância
a falhas por interceptor: a sétima regra (`toleranciaAFalhasNaoPodeSerConfigurada`) continua verde,
e nenhuma chave dos namespaces que ela proíbe voltou ao arquivo. A distinção é a que o 064 pedia —
o que ele removeu configurava um `@Retry` que já não existia; o que entra aqui configura a cadeia
Mutiny que de fato roda.

`comRepeticao` deixou de ser `static` para alcançar o campo injetado. `MAXIMO_DE_REPETICOES`
**continua constante**: a contagem é a política do [ADR 0001](../../adr/0001-politica-de-falhas.md)
e não se ajusta por perfil; a espera é o preço dela.

**Um achado que custou uma medição: `0s` não serve.** O Mutiny recusa backoff zero com
`IllegalArgumentException: initialBackOff must be greater than zero`, e a recusa não chega no boot
— chega na subscrição, como 500 na borda, com três dos quatro cenários falhando. O piso disponível
é **1 ms**, e o motivo está em comentário no `.properties` para que ninguém tente "arredondar" de
volta para zero.

**O javadoc do teste** descreve agora a proteção que existe hoje (`onFailure().retry()` do Mutiny,
3 repetições, jitter de 10%, nenhum interceptor), o que o cenário custa com e sem o ajuste, e o
que a espera curta deixa de cobrir: que os 2 s do ADR sejam os 2 s. Esse número é guardado pelo
default do `@ConfigProperty`, não por cenário.

**Validações**: `EnvioResisteABlipDoArmazenamentoTest` verde nos quatro cenários; `videos`
completo verde (141 testes); `notificacao` verde (29); `scripts/verifica-testes-arquiteturais.sh`
e `scripts/verifica-ackmanual.sh` passam.

**O critério "`./mvnw test` verde a partir da raiz" não foi satisfeito, e não por este ticket.**
O `extracao` reprova 5 cenários nesta máquina — `SondagemSemFluxoDeVideoTest` e três do
`CucumberTest`, mais o `ExtracaoEstacionamentoTest` — porque **`ffmpeg` e `ffprobe` não estão
instalados no host**. Sem o binário, o `ProcessBuilder` falha ao arrancar e a falha, que deveria
ser permanente, é classificada como transitória.

**Raiz única, sintomas diferentes**, e vale registrar para quem for reproduzir: só o
`SondagemSemFluxoDeVideoTest` falha pela assinatura direta
(`FalhaTransitoriaDeExtracaoException` onde espera `FalhaPermanenteDeExtracaoException`); o
`ExtracaoEstacionamentoTest` falha em `expected: not <null>`, porque a falha reclassificada
recircula em vez de estacionar, e os três cenários do `CucumberTest` falham cada um no seu step
do feature. Uma causa, quatro mensagens.

Verificado que é pré-existente e alheio a este trabalho: com as mudanças em *stash*, no `HEAD`
limpo, o `SondagemSemFluxoDeVideoTest` reprova igual. Nenhum arquivo do `extracao` foi tocado
aqui.

## O que este ticket não entrega

**As outras duas cópias de `comRepeticao` pagam o mesmo custo, e ficaram como estão.** Medido, ao
inspecionar as cópias como o `AGENTS.md` § *As cópias deliberadas entre serviços* manda:
`RepeticaoNoMinioTest` do `extracao` leva **14,30 s** e `RepeticaoNoSmtpTest` do `notificacao`
leva **10,19 s**, pela mesma aritmética. Estender o ajuste às duas é mecânico e preserva o
contrato, mas está fora do que este ticket pediu — o escopo dele é o custo que o 048 comprou na
borda do `videos`. Fica medido e escrito aqui; vira ticket próprio se o mantenedor quiser, pelo
mesmo critério do [027](027-melhorias-medidas.md).
