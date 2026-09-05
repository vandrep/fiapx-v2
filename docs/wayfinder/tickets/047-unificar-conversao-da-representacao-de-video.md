# Unificar a conversão da representação de Vídeo

- id: 047
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Origem

Possível Código Duplicado identificado no eixo Standards da revisão de
`3a3ec95...270b891`, convertido em ticket com aprovação do usuário. Os presenters
individual e paginado repetem a conversão dos sete campos da representação pública.
É uma heurística de manutenção: não foi identificado defeito funcional atual.

## O que entregar

Envio, consulta individual e listagem preservam uma única representação pública de Vídeo,
com uma conversão compartilhada dentro da camada de apresentação. Alterar essa conversão
não exige manter duas cópias do mapeamento em sincronia.

## Condições de aceite

- [x] Reutilizar a conversão da representação individual ao montar os itens da página,
  respeitando as responsabilidades dos presenters e as regras arquiteturais.
- [x] Preservar nomes, tipos e valores dos sete campos públicos, inclusive campos nulos
  e os códigos de estado e motivo.
- [x] Verificar que envio, consulta e listagem mantêm a representação prevista no contrato
  HTTP, considerando o estado do Vídeo no momento de cada resposta.
- [x] Manter as chaves de armazenamento fora da representação pública.
- [x] Executar as verificações existentes de apresentação e borda e a suíte da raiz;
  acrescentar cobertura apenas se houver lacuna relevante para a mudança.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

A conversão dos sete campos saiu dos dois presenters e virou `RepresentacaoDeVideo.de`,
classe package-private na camada de apresentação, sem estado e sem construtor público. O
`VideoPresenterAdapter.present` chama ela, e o `VideosPaginadosPresenterAdapter` monta cada
item da página com a mesma referência (`.map(RepresentacaoDeVideo::de)`), no lugar do
`paraViewModel` privado que repetia o mapeamento. Não há mais duas cópias para manter em
sincronia; o `Pagina` continua sendo desmontado pelo presenter paginado, que é a
responsabilidade dele.

Classe própria, e não método do presenter individual: os dois eixos do review apontaram que
uma conversão compartilhada morando na classe do caso *individual* esconde o papel dela — e
faria o presenter paginado depender do vizinho concreto. Também não é delegação a uma
instância de `VideoPresenter`: esse presenter é `@RequestScoped` e guarda o resultado de
**uma** requisição em campo mutável, e a listagem converte em laço.

O `VideoViewModel` não ganhou a fábrica: ele é o record do contrato, e mantê-lo sem conhecer
o `VideoDTO` deixa a tradução domínio → JSON onde o javadoc dele já dizia que ela mora.

Três verificações novas, porque a lacuna era real: nada comparava as duas representações, e
nada cobrava as chaves publicadas.

- `RepresentacaoDeVideoTest` (unitário, sem Quarkus): as esperas são `VideoViewModel`
  escritos campo a campo, não o resultado da conversão sob julgamento — senão o teste
  concordaria com qualquer mapeamento errado. Cobre `finalizadoEm` saindo como
  `concluidoEm`, `motivo` como código, campo nulo que continua nulo, e a igualdade entre
  item da listagem e consulta individual nos três estados.
- Cenário BDD do Vídeo CONCLUIDO: o item da listagem tem de ser **igual** ao corpo do
  `GET /videos/{id}` e ter exatamente as sete chaves públicas. É o estado com mais o que
  vazar — `chavePacote`, `quantidadeFrames` e `tamanhoPacoteBytes` existem no banco e não
  estão no contrato.
- Cenário BDD do Vídeo FALHOU: `motivo` é o campo mais frágil da representação (sai como
  código, nunca frase), e antes nenhum cenário o fazia atravessar o JSON. Agora consulta e
  listagem publicam `SEM_FLUXO_DE_VIDEO`, com as mesmas sete chaves.

O corpo do `202` ganhou a checagem das sete chaves. A igualdade entre o corpo do envio e a
consulta seguinte **não** entrou, e o motivo é um achado: o `202` devolve o `recebidoEm` do
`Instant` em memória, com nanossegundos, e o `GET` devolve o mesmo campo relido do Postgres,
truncado em microssegundos — `...:25.487901409Z` contra `...:25.487901Z`. Mesmo Vídeo, dois
valores para o mesmo campo. É precisão de persistência, não conversão de apresentação, e
mudar o valor publicado contraria a condição de preservar valores deste ticket; fica
registrado para virar ticket próprio.

**Suíte verde a partir da raiz**, com infraestrutura real: 125 testes no `videos` (16 cenários
BDD), 268 no `extracao` e 24 no `notificacao`. Como nos tickets 045 e 046, o Keycloak da
stack de Compose desta máquina ocupa a 8081, a porta de teste do Quarkus; rodei com `-Dquarkus.http.test-port=0` em
vez de derrubar a stack. Circunstância da máquina, não do projeto.
