# Chaves órfãs de Fault Tolerance sobrevivem à regra do 061

- id: 064
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7`, convertido em ticket com
aprovação do usuário.

## O problema

O [ticket 061](061-travamento-raro-com-o-sdk-desligado.md) tirou a extensão de Fault Tolerance
dos três `pom.xml` e as anotações do código de produção, e o AGENTS.md ganhou a sexta regra:
nada de tolerância a falhas por interceptor. Sobraram duas chaves de configuração do
MicroProfile Fault Tolerance no `application.properties` do `videos`, no perfil de teste, que
configuram um `@Retry` que não existe mais. São as duas últimas ocorrências do assunto em todo
o repositório fora de `docs/`.

O comentário logo acima delas afirma o contrário do que hoje é verdade — que o `maxRetries`
"esse sim sob teste — segue o do codigo" — e o javadoc do `EnvioResisteABlipDoArmazenamentoTest`
ainda descreve a proteção como vinda "do `@Retry` do adapter". Quem ler qualquer um dos dois
conclui que o interceptor voltou.

A razão de isso ter passado é estrutural, e é o que dá valor ao ticket: a sexta regra é cobrada
pelo `ArchitectureConstraintsTest`, que lê **fontes Java**. Configuração de Fault Tolerance
não é import nem anotação, então o build não a enxerga — o mesmo tipo de ponto cego que o
ticket 034 fechou para `publish-confirms`.

## O que entregar

- As duas chaves fora do `application.properties` do `videos`, e a suíte segue verde: se algum
  cenário depender do `delay` zerado, a dependência era do interceptor que já saiu, e o ticket
  registra o que a passou a substituir.
- O comentário acima delas e o javadoc do teste do blip descrevendo a proteção que existe hoje
  (`onFailure().retry()` do Mutiny), não a que saiu.
- A sexta regra cobrada também sobre `application.properties`, nas **três cópias** do
  `ArchitectureConstraintsTest` — byte a byte idênticas, como manda o AGENTS.md, com
  `scripts/verifica-testes-arquiteturais.sh` verde. A regra do ticket 034 é o modelo de como se
  lê o `.properties` de dentro do teste.

## Critérios de aceite

- [ ] `grep -ri faulttolerance` fora de `docs/` não acha nada em código, config ou pom
- [ ] O teste arquitetural reprova quando uma chave de Fault Tolerance é reintroduzida no `.properties`
- [ ] As três cópias do teste seguem idênticas; `./mvnw test` verde a partir da raiz
