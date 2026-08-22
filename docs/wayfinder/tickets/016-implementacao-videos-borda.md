# Implementação do serviço videos: borda HTTP e persistência

- id: 016
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por: 009, 011, 019

## Question

O maior dos três serviços, dividido em dois tickets porque não cabe numa sessão. Este é a
metade síncrona: o que o avaliador vê no Swagger UI. O ticket 017 faz a mensageria.

Implementar, test-first, conforme
[`docs/contratos/http-videos.md`](../../contratos/http-videos.md) e o `AGENTS.md`:

- As quatro operações do `VideosResource`, com o `sub` e o claim `email` vindos do
  `JsonWebToken` injetado e passados como `String` ao controller — o `core` não vê JWT.
- Upload em streaming para o MinIO: `AsyncRequestBody.fromFile(FileUpload.uploadedFile())`.
  Nada de `toBytes`/`fromBytes` (ticket 005). `max-body-size=200M` e
  `uploads-directory=/var/fiapx/uploads` sobre o volume `fiapx-uploads` (ticket 011).
- Download em streaming: `RestMulti.fromUniResponse` + `toPublisher()`.
- Persistência do Vídeo conforme o modelo e o script do ticket 009, incluindo e-mail do
  dono e nome do arquivo original — o `VideoFalhou` depende dos dois. A `VideoEntity` mapeia
  também `comando_publicado_em` e `falha_publicada_em` (ADR 0003): quem as **usa** é o 017,
  mas em `%prod` o `validate` derruba o serviço no boot se elas não estiverem mapeadas.
- Validação de borda do upload: extensão em `mp4`/`avi`/`mov`/`mkv`/`webm` **e**
  content-type `video/*`, devolvendo `415` (ticket 011). É verificação **declarativa** — a
  prova de que o arquivo é decodificável é do `extracao`, e este serviço **não** roda
  `ffprobe`.
- `ArquivoGateway` monta as chaves (ticket 009): bucket `videos` com
  `{idVideo}/original.{ext}` e bucket `pacotes` com `{idVideo}.zip`, preservando a extensão
  original. A chave **não** carrega o dono — a autoridade é `video.dono_sub`.
- Os `ExceptionMapper` de problem+json, as seis situações da tabela do contrato.
- `@SecurityScheme` oauth2 com fluxo `password`, URL do realm **configurável** (o Dev
  Services sorteia porta em teste), e as anotações OpenAPI do contrato.
- Cucumber pela borda HTTP; `ArchitectureConstraintsTest` do módulo passando.

- O `BaixarPacoteUseCase` trata o Pacote expirado conforme o **ticket 019** — a retenção de
  7 dias do MinIO (ticket 011) contra uma tabela que nunca perde linhas.

Fora deste ticket: publicar `ExtrairVideo`, consumir os eventos de progresso, publicar
`VideoFalhou` e a varredura de reconciliação — tudo isso é o 017. Aqui o Vídeo entra e fica
em `RECEBIDO`.

## Notas herdadas do ticket 019

O `BaixarPacoteUseCase` tem **dois** caminhos de indisponibilidade, não um: `409` quando o
Vídeo não está `CONCLUIDO`, `410 Gone` quando está mas o objeto não existe mais no MinIO
(retenção de 7 dias do ticket 011).

1. O adapter precisa distinguir `NoSuchKey` de erro genérico do S3. Mapear qualquer falha do
   MinIO para `410` faria um MinIO fora do ar mandar o cliente desistir para sempre. Erro de
   infraestrutura continua `500`.
2. O `GET` que descobre a ausência **não grava nada** no Postgres — sem write-back, sem
   anular `chave_pacote`.
3. O cenário BDD do `410` apaga o objeto do bucket pelo step; não há como esperar sete dias.


## Resolução

O serviço `videos` síncrono, test-first: 66 testes verdes, dos quais **34 rodam sem Docker**
— o `core` inteiro é testável com dublês em memória, que é a prova de que a separação de
camadas paga o próprio preço. Os 19 cenários BDD exercitam a borda com token real do
Keycloak, MinIO real (LocalStack) e Postgres real, todos por Dev Services.

O que ficou como o ticket pediu não está repetido aqui. O que segue são as seis decisões que
a implementação **teve** de tomar porque a especificação não as cobria, e as duas correções
que ela devolve ao mapa.

### 1. O download não devolve `Uni` — e a regra arquitetural cedeu, com medição

O ticket mandava `RestMulti.fromUniResponse`, e o `ArchitectureConstraintsTest` exigia que
todo método público de `Resource` devolvesse `Uni`. **Os dois não cabem juntos**, e a
descoberta custou três execuções da suíte.

O `PublisherResponseHandler` do RESTEasy Reactive olha `getResult()` — o retorno **direto**
do método. Foram medidos os dois contornos que preservariam o `Uni`:

| Tentativa | O que aconteceu |
|---|---|
| `Uni<Response>` com `Multi` de entidade | nenhum writer aceita; a conexão pendura até o read timeout |
| `Uni<RestMulti<byte[]>>` | transmite, mas o corpo é `…RestMulti$SyncRestMulti@679e3ca6` — o `toString()` do objeto, 60 bytes |

Então a regra foi relaxada para aceitar **`Uni` ou `RestMulti`**, nas três cópias, byte a
byte idênticas, com o porquê no próprio arquivo e em [`AGENTS.md`](../../../AGENTS.md). Não é
afrouxamento: o que a regra protege é *nada de retorno bloqueante na borda*, e os dois tipos
cumprem isso igualmente. É a **terceira** asserção do template a ceder, e a primeira que cede
por um fato medido em vez de por diferença de topologia.

### 2. A sessão do Hibernate saiu do Resource e foi para o adapter

Consequência direta da anterior, e melhor do que o que estava escrito: `@WithSession` **exige**
retorno `Uni` e derruba o boot com `DeploymentException` se não for.

A saída foi `Panache.withSession(...)` dentro do `VideoDataSourceAdapter`. Ficou melhor por um
motivo que não tem nada a ver com a anotação: no Resource, a sessão viveria enquanto o Pacote
inteiro trafega — **segurar conexão de banco durante 1,5 GB de streaming**. No adapter ela
fecha quando a consulta acaba, antes do primeiro byte sair. `withSession` é reentrante, então
o `@WithTransaction` que continua no `POST` convive sem conflito.

### 3. O future do SDK da AWS perde o contexto Vert.x — e isso quebra o `INSERT`

O achado mais caro do ticket, e o que mais fácil seria escrever errado sem teste.

O `EnviarVideoUseCase` grava no MinIO e **depois** no banco (a ordem fixada no ticket 009). O
`putObject` do `S3AsyncClient` completa o future na event loop **do próprio SDK**, e o passo
seguinte herda aquela thread — perdendo o contexto duplicado onde o Panache guarda a sessão. O
`INSERT` morria com `No current Vertx context found`.

A ponte ficou no `ArquivoMinioAdapter`, que é onde a thread estranha aparece. A alternativa
seria o `core` saber em que ordem gateways podem ser encadeados, que é exatamente o que ele
não deve saber.

### 4. `Video.novo` não recebe a chave: ele a habilita

O ticket 009 se contradiz num ponto — diz que `Video.novo(nome, tamanho, dono, chaveVideo)`
gera o `UUID` **e** que `ArquivoGateway.gravarVideo(idVideo, ...)` devolve a chave que `novo`
recebe pronta. Não dá: a chave é `{idVideo}/original.{ext}`, então o id precede a chave.

Resolvido preservando a intenção das duas metades: `Video.novo(nome, tamanho, dono)` gera
identidade e instante, e `video.armazenadoEm(chave)` fecha a criação depois do `putObject`. A
identidade continua no domínio, a convenção continua no adapter, e a ordem
*objeto → linha* continua garantida — agora por construção, porque não há como persistir um
Video sem chave.

### 5. Onde a validação de formato mora

`FormatoDoArquivo`, no `core`, e não na borda. A lista de extensões é política de domínio e
merece teste unitário; o Resource só traduz a exceção em `415`. O mesmo value object entrega
`extensaoDe` ao adapter (que a preserva na chave, para os demuxers do ffmpeg) e
`nomeDoPacotePara` ao download — as três leituras da extensão num lugar só.

### 6. Os seis mappers num arquivo, não em seis

A tabela de erros do contrato tem seis linhas de três linhas cada. Espalhá-las por seis
arquivos esconderia a tabela em vez de mostrá-la. O mapper de `Throwable` **não** intercepta
401/403: o JAX-RS escolhe o mapper mais específico, e as exceções de segurança já têm os seus
— verificado pelo cenário BDD que pede a listagem sem token e recebe `401`.

## O que este ticket devolve ao mapa

### Um entregável que era névoa: `docker/keycloak/realm-export.json`

**Foi preciso criá-lo para que qualquer teste de borda existisse.** O realm padrão do Dev
Services **não emite o claim `email`** (verificado: o token traz `sub`, `upn` e `groups`, e
nada mais), e sem `email` o `Dono` não se monta.

O arquivo criado é **mínimo e só carrega o que já estava decidido**: o claim `email` (exigido
pelo ticket 007) e *direct access grants* (exigido pelo ticket 008), mais dois usuários de
demonstração e a role `usuario`. As perguntas que a névoa de fato guarda — audience mapper,
`token.audience`, elenco final de usuários e clients — **continuam abertas**, e a entrada
"Configuração do realm Keycloak" segue em *Ainda não especificado*, agora com um arquivo para
editar em vez de um do zero.

Confirmação colateral da armadilha do ticket 004: no token emitido, `sub` é
`30cccd4a-…` e `preferred_username` é `demo`. Quem tivesse usado `getName()` teria escrito um
bug silencioso de autorização que nenhum teste de caminho feliz pegaria.

### Uma linha no Dockerfile que o Compose teria descoberto tarde

`RUN mkdir -p /var/fiapx/uploads && chown -R 185:185 /var/fiapx`. Um volume nomeado vazio
herda dono e permissão do ponto de montagem **da imagem**; sem isso ele nasceria de `root` e o
processo (`USER 185`) não gravaria os 200 MB do upload. É o tipo de falha que só aparece com o
Compose de pé.

## O que ficou de fora, e por quê

- **As três colunas de mensageria** (`marcarIniciado`/`marcarConcluido`/`marcarFalhou` no
  `VideoGateway`) não existem ainda: são do ticket 017. As transições **da entidade** foram
  implementadas e testadas, porque meia entidade seria pior que nenhuma — e o grafo do
  ADR 0002 é justamente o que precisa de teste unitário puro.
- **As marcas do ADR 0003** estão mapeadas na `VideoEntity` e ninguém as lê, exatamente como o
  ticket pediu: em `%prod` o `validate` derrubaria o boot sem elas.
- O `Video` do `core` **não** as carrega. Quem publica é o 017, e é ele que decide se elas são
  atributo de domínio ou detalhe do adapter.
