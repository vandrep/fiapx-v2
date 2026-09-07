# Retry no acesso ao MinIO pela borda do videos

- id: 048
- label: wayfinder:bug
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Standards da revisão de `3a3ec95...b4672ff`, convertido em ticket com
aprovação do usuário. O ADR 0001 justifica a política de falhas afirmando que blips de I/O
de segundos contra MinIO e Postgres são cobertos por `@Retry` no adapter. O `extracao` e o
`notificacao` cumprem, cada um com um bean de cliente isolado. O `videos` não: a extensão de
fault tolerance nem sequer está declarada no módulo, e o adapter de arquivo sobe e baixa do
MinIO sem nenhuma proteção. É divergência entre o ADR e o código, não defeito medido.

## O que entregar

Uma instabilidade curta do armazenamento durante o envio ou o download deixa de virar erro
interno para quem chamou a API. Ou o caminho síncrono da borda passa a se comportar como o
que o ADR 0001 descreve, ou o ADR passa a declarar por escrito que a borda é exceção
deliberada — e diz por quê.

## Condições de aceite

- [x] Decidir entre implementar o retry na borda ou registrar a exceção no ADR 0001, com a
  razão explícita no documento.
- [x] Se implementado: aplicar a proteção no ponto de fronteira com o armazenamento,
  respeitando as regras de camada e o isolamento de bean que os outros dois serviços já usam.
- [x] Se implementado: verificar que uma falha transitória do armazenamento durante o envio
  não chega ao chamador como erro interno, e que uma falha persistente continua chegando.
- [x] Preservar os status e corpos previstos no contrato HTTP para o envio e o download.
- [x] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

**Implementado, não excetuado.** A borda síncrona é onde a proteção pesa *mais*, não menos:
atrás dela não há fila quorum para reentregar, então o blip que ela não absorve já saiu como
`500` para quem chamou a API. O ADR 0001 continua valendo como está escrito — agora os três
serviços o cumprem, e não dois.

`videos/pom.xml` ganhou a `quarkus-smallrye-fault-tolerance`, e o acesso ao MinIO ganhou o
mesmo desenho de dois beans que o `extracao` e o `notificacao` já usam. O novo
`ArquivoMinioClient` carrega as duas idas ao armazenamento — `gravar` e `abrir` — com
`@Retry(maxRetries = 3, delay = 2s)` e `@AsynchronousNonBlocking`; o `ArquivoMinioAdapter`
continua sendo o único que conhece bucket e convenção de chave, e chama o cliente **de fora**,
que é o que faz o interceptor disparar (self-invocation ignoraria o proxy do CDI). O adapter
segue implementando o `ArquivoGateway` e devolvendo `CompletableFuture`, com a ponte
`noContextoDeChamada` intacta — ela agora também cobre a retomada na thread do scheduler do
fault tolerance, e não só a event loop do SDK.

Um cuidado que o `extracao` não precisa ter: lá, qualquer falha do MinIO é transitória por
definição; aqui, o objeto ausente é o `410` do contrato (ticket 019). Por isso o
`NoSuchKeyException` vira `Optional.empty()` **dentro** do método anotado, antes de o
interceptor ver o resultado — a chave que não existe completa com sucesso e não gasta
tentativa. Repetir três vezes um `NoSuchKey` seguraria por segundos um desfecho já conhecido
no primeiro erro. Qualquer outra falha do armazenamento continua falha, e é essa que o retry
cobre.

`EnvioResisteABlipDoArmazenamentoTest` é a verificação dos dois lados do aceite, e entra pela
borda: `POST /videos` e `GET /videos/{id}/pacote` com o `S3AsyncClient` trocado por um dublê
instável. Quatro cenários, os dois caminhos síncronos vezes os dois desfechos. Falhando as
duas primeiras chamadas, o envio responde `202` e o download responde `200` com o Pacote
**inteiro**; falhando sempre, os dois respondem `500` — e o do envio é cobrado também no
corpo, `application/problem+json` com `title: Erro interno`, que é o que o contrato prevê para
qualquer outra falha.

Escrito antes da implementação, o teste do envio reprovava exatamente onde devia
(`Expected status code <202> but was <500>`), e o cenário persistente já estava verde desde o
começo — o que é o ponto: a mudança não afrouxou o erro que tem de chegar. O par do download
foi conferido por mutação depois: retirado o `@Retry` de `abrirSeExistir`, só
`blipDoArmazenamentoDuranteODownloadNaoChegaAoChamadorComoErroInterno` cai, e os outros três
seguem verdes.

O dublê não delega ao MinIO de verdade porque o proxy do CDI já aponta para ele durante o
teste, e delegar reentraria em si mesmo; por isso ele também serve os bytes do Pacote. O que
está sob julgamento aqui é o desfecho da requisição — o armazenamento de verdade é exercitado
pelos cenários BDD, que continuam passando sem alteração.

O `delay` cai a zero sob `%test`, para a suíte não parar 2s por tentativa. A chave **precisa
nomear o método**, e isso foi medido, não suposto: a forma por classe
(`...ArquivoMinioClient/Retry/delay`) é ignorada — com o valor em 10s o cenário persistente
continuou nos ~7s dos 3 × 2s do código; com `.../gravar/Retry/delay=10` ele foi a 31s, que é a
prova de que a chave pegou. O `maxRetries` — esse sim sob julgamento — continua sendo o do
código, e os 2s valem em produção.

Contrato HTTP intocado: nenhum status, corpo ou cabeçalho mudou, e os 16 cenários BDD do envio,
da consulta e do download passam sem alteração.

Fica registrado o que **não** foi feito: `gravar` reenvia o arquivo até quatro vezes, e o teto
do upload é 200 MB. Não há `@Timeout` na borda, nem havia antes; é custo que os dois workers
não têm, e nenhum número foi medido a respeito. Não entrou aqui porque mudaria a política de
falhas do ADR 0001 sem medição que a justifique.

**Suíte verde a partir da raiz**, com infraestrutura real: 129 testes no `videos` (os 125 de
antes mais os quatro do blip), 268 no `extracao` e 24 no `notificacao`.
