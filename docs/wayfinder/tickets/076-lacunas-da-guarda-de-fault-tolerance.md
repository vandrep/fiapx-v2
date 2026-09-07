# Lacunas e ofuscação na guarda de tolerância a falhas

- id: 076
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Uma chave `smallrye.faulttolerance.*` no `.properties` reprova o build
- [ ] Nenhuma constante do teste partida por concatenação para escapar de busca textual
- [ ] O alcance da regra sobre variável de ambiente está escrito, alcançado ou recusado
- [ ] As três cópias seguem idênticas; `./mvnw test` verde a partir da raiz
