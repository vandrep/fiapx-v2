# Lacunas e ofuscação na guarda de tolerância a falhas

- id: 076
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Achado dos dois eixos da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 064](064-chaves-orfas-de-fault-tolerance.md) fechou o ponto cego que deixava chave de
Fault Tolerance sobreviver ao interceptor que saiu no
[061](061-travamento-raro-com-o-sdk-desligado.md). A regra
`toleranciaAFalhasNaoPodeSerConfigurada` existe e funciona, mas ficou com duas bordas abertas e
pagou um preço que não precisava pagar.

**As lacunas.** O padrão cobre a forma do MicroProfile (`.../Retry/...`) e o namespace
`quarkus.fault-tolerance`. Fica de fora o namespace `smallrye.faulttolerance.*`, que a extensão
também aceita, e fica de fora a forma por variável de ambiente
(`MP_Fault_Tolerance_*`, `SMALLRYE_FAULTTOLERANCE_*`) — o mesmo limite que o
[034](034-publish-confirms-sem-guarda.md) documentou para `publish-confirms` e que o
[038](038-override-de-canal-por-variavel-quebra-o-boot.md) mostrou ser real neste repositório,
onde o Compose sobrescreve canal por variável.

**A ofuscação.** As constantes do teste estão partidas ao meio:

```java
// Partido para a busca textual do ticket 064 nao encontrar o proprio guarda.
"(?m)^import\\s+(static\\s+)?(org\\.eclipse\\.microprofile\\.fault" + "tolerance"
```

e `"ApplyFault" + "Tolerance"` na lista de anotações. O motivo é fazer passar um critério de
aceite do 064 — `grep -ri faulttolerance` fora de `docs/` não acha nada. Ele não passa mesmo
assim, porque o `AGENTS.md` casa com a busca. Sobra o custo: a guarda ficou ilegível para quem
a lê, e invisível para a própria auditoria textual que a motivou. Uma exclusão explícita do
arquivo do teste na busca é mais honesta que uma concatenação que engana o `grep`.

## O que entregar

- O padrão cobrindo `smallrye.faulttolerance.*` além do que já cobre.
- Uma decisão registrada sobre a forma por variável de ambiente: ou a guarda a alcança, ou o
  limite fica escrito no Javadoc da regra, como o 034 fez com o dele. Não deixe implícito.
- As constantes de volta a literais legíveis, e o critério de busca do 064 reformulado para
  excluir o arquivo que implementa a guarda — se ele continuar valendo.
- As **três cópias** do `ArchitectureConstraintsTest` byte a byte idênticas, com
  `scripts/verifica-testes-arquiteturais.sh` verde.

## Critérios de aceite

- [x] Uma chave `smallrye.faulttolerance.*` no `.properties` reprova o build
- [x] Nenhuma constante do teste partida por concatenação para escapar de busca textual
- [x] O alcance da regra sobre variável de ambiente está escrito, alcançado ou recusado
- [x] As três cópias seguem idênticas; `./mvnw test` verde a partir da raiz

## Resolução

**A lacuna do namespace.** `TOLERANCIA_A_FALHAS_CONFIGURADA` ganhou uma terceira alternativa,
ao lado da forma do MicroProfile (`.../Retry/...`) e de `quarkus.fault-tolerance`:
`smallrye\.faulttolerance(?:\.|[=:\s]|$)`, mesmo tratamento de separador e fim de linha que as
outras duas já tinham. Ciclo TDD observado à mão: `smallrye.faulttolerance.globalThreadPoolSize=42`
acrescentado ao `application.properties` do `videos` e do `extracao` reprovou
`toleranciaAFalhasNaoPodeSerConfigurada` antes da mudança de regex (vermelho) e passou a
reprovar com ela (a chave nomeada na mensagem, sem o valor); removida a linha, o teste volta a
verde.

**O alcance da variável de ambiente.** Recusado, por escrito, no Javadoc de
`TOLERANCIA_A_FALHAS_CONFIGURADA`: a regra lê o `.properties` do próprio serviço, então
`MP_Fault_Tolerance_*`/`SMALLRYE_FAULTTOLERANCE_*` passam por fora — o mesmo limite que o
ticket 034 já documentou para `publish-confirms`. Aqui ele pesa menos: a extensão saiu dos três
`pom.xml` no ticket 061, então a variável não muda comportamento nenhum sem que alguém
reintroduza a dependência primeiro, e nenhum `docker-compose*.yml` deste repositório declara
uma variável nesse formato hoje (conferido por grep). Diferente do canal de mensageria — que
sempre existe e só troca de forma —, aqui não há um caso real no repositório hoje que o limite
esconda.

**A ofuscação.** As duas constantes partidas por concatenação
(`"fault" + "tolerance"`, `"ApplyFault" + "Tolerance"`) voltaram a literais legíveis. O critério
de aceite do ticket 064 que motivava o truque foi reformulado nele mesmo: em vez de
`grep -ri faulttolerance` fora de `docs/` — que nunca passou de verdade, porque o `AGENTS.md`
documenta a regra em prosa e fica fora de `docs/` — o critério agora restringe a busca aos
tipos de arquivo que sempre foram a intenção (`--include="*.java" --include="*.xml"
--include="*.properties"`), e aí só acha as três cópias do próprio guarda, o que é esperado:
elas citam o termo de propósito, para reprová-lo.

**As três cópias.** Editado em `videos`, copiado byte a byte para `extracao` e `notificacao`;
`scripts/verifica-testes-arquiteturais.sh` confirma identidade antes e depois.

### Validação

- Vermelho/verde da regra nova, à mão, nos dois serviços com canal de saída: chave
  `smallrye.faulttolerance.globalThreadPoolSize=42` reprova `toleranciaAFalhasNaoPodeSerConfigurada`;
  removida, o teste passa.
- `grep -ril faulttolerance --include="*.java" --include="*.xml" --include="*.properties"` a
  partir da raiz: só as três cópias de `ArchitectureConstraintsTest.java`.
- `./mvnw test` na raiz, com Docker, ffmpeg e ffprobe reais: BUILD SUCCESS, 140 testes em
  `videos`, 277 em `extracao`, 29 em `notificacao`, 0 falhas e 0 erros nos três.
