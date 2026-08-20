# Upload multipart reativo e cliente MinIO/S3

- ticket: [005](../wayfinder/tickets/005-upload-download-minio.md)
- data: 2026-08-20
- alvo: Quarkus **3.31.3**, Java 21, stack reativa (`quarkus-rest`, Hibernate Reactive,
  `quarkus-reactive-pg-client`), MinIO no Docker Compose.

Todas as afirmações abaixo saem de fonte primária: código-fonte das versões exatas
(Quarkus 3.31.3 e `quarkus-amazon-s3` 3.14.1, que é a versão alinhada pelo BOM da
plataforma 3.31.3), documentação oficial do Quarkus, do AWS SDK for Java 2.x, da AWS e da
MinIO. Nenhum blog foi usado.

---

## 0. Resumo executivo

| Pergunta | Resposta curta |
|---|---|
| Upload grande em `quarkus-rest` reativo | Funciona. O Vert.x grava a parte de arquivo **em disco**, não em memória. Mas o limite padrão de corpo é **10 MB** e precisa ser levantado. |
| Cliente S3 | `io.quarkiverse.amazonservices:quarkus-amazon-s3` **3.14.1** (alinhado pelo `quarkus-amazon-services-bom` 3.31.3). Tem `S3AsyncClient` (Netty) — é o que a stack reativa exige. |
| Apontar para MinIO | `quarkus.s3.endpoint-override` + `quarkus.s3.path-style-access=true` + credenciais estáticas. |
| Presigned URL para download | **Viável**: o `S3Presigner` já é um bean injetável da extensão e herda `endpoint-override`/`path-style-access`. Implicação: a URL é um *bearer token* — a autorização do `videos` só é verificada na emissão. Mitigação: TTL curto. |
| Streaming sem carregar na memória | `RestMulti.fromUniResponse(...)` + `AsyncResponseTransformer.toPublisher()` (download) e `AsyncRequestBody.fromFile(Path)` (upload). Nunca `toBytes()`. |
| Dev Services | Dev Services nativo do `quarkus-amazon-s3` sobe **LocalStack**, não MinIO. Para MinIO em teste: **Compose Dev Services** do Quarkus ou `MinIOContainer` do Testcontainers. |

---

## 1. Upload de arquivo grande em `quarkus-rest` reativo

### 1.1 `@RestForm` + `FileUpload` grava em disco, não em memória

A forma canônica documentada pelo guia oficial do Quarkus REST:

```java
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@POST
public void multipart(@RestForm String description,
        @RestForm("image") FileUpload file) {
    // file.uploadedFile() -> java.nio.file.Path
}
```

O que garante que não vai para a memória é a configuração do body handler do Vert.x.
Do código-fonte de `BodyConfig` na tag **3.31.3**
([BodyConfig.java](https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/BodyConfig.java)):

| Propriedade | Default | Javadoc (verbatim) |
|---|---|---|
| `quarkus.http.body.handle-file-uploads` | `true` | "Whether the files sent using `multipart/form-data` will be stored locally. If true, they will be stored in the uploads directory and made available via `RoutingContext.fileUploads()`" |
| `quarkus.http.body.uploads-directory` | `${java.io.tmpdir}/uploads` | "The directory where files sent using `multipart/form-data` should be stored." |
| `quarkus.http.body.delete-uploaded-files-on-end` | `true` | "Whether uploaded files should be removed after serving the request." |
| `quarkus.http.body.merge-form-attributes` | `true` | — |
| `quarkus.http.body.preallocate-body-buffer` | `false` | "Whether the body buffer should pre-allocate based on the `Content-Length` header." |

Consequências práticas para o `videos`:

1. Um vídeo de 500 MB vira um arquivo em `${java.io.tmpdir}/uploads` com nome UUID. O
   guia oficial é explícito: *"When handling file uploads, it is very important to move
   the file to permanent storage (like a database, a dedicated file system or a cloud
   storage) in your code"* — no nosso caso, subir para o MinIO antes de responder.
2. Como `delete-uploaded-files-on-end=true` é o default, o temporário some quando a
   resposta é enviada. **Isso obriga o `PutObject` a completar antes de o `Uni` do
   endpoint resolver** — o que é exatamente o desenho abaixo. Não dá para "responder 202 e
   subir depois" reutilizando o `Path` do `FileUpload`.
3. O contêiner do `videos` no Compose precisa de espaço em disco (ou volume) para
   `uploads-directory`. Mapear explicitamente
   `quarkus.http.body.uploads-directory=/var/lib/fiapx/uploads` e montar um volume é mais
   honesto do que depender de `/tmp` do contêiner.
4. **Nunca** declarar a parte do arquivo como `byte[]`, `String` ou `InputStream` lido
   inteiro — isso desfaz a economia. `FileUpload` ou `java.nio.file.Path`.

Fonte: [Quarkus REST — Handling multipart form data](https://quarkus.io/version/3.31/guides/rest#multipart).

### 1.2 O limite que vai doer: `max-body-size` = 10 MB

Do código-fonte de `ServerLimitsConfig` na tag **3.31.3**
([ServerLimitsConfig.java](https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/ServerLimitsConfig.java)):

| Propriedade | Tipo | Default |
|---|---|---|
| `quarkus.http.limits.max-body-size` | `Optional<MemorySize>` | **`10240K`** (10 MB) |
| `quarkus.http.limits.max-form-attribute-size` | `MemorySize` | `2048` (bytes) |
| `quarkus.http.limits.max-form-fields` | `int` | `256` |
| `quarkus.http.limits.max-form-buffered-bytes` | `MemorySize` | `1K` |
| `quarkus.http.limits.max-header-size` | `MemorySize` | `20K` |
| `quarkus.http.limits.max-parameters` | `int` | `1000` |
| `quarkus.http.limits.max-chunk-size` | `MemorySize` | `8192` |

Ou seja: **sem tocar em nada, um vídeo de centenas de MB é rejeitado com `413 Payload Too
Large`**. É preciso decidir um teto e configurá-lo:

```properties
# videos/src/main/resources/application.properties
quarkus.http.limits.max-body-size=1G
quarkus.http.body.uploads-directory=/var/lib/fiapx/uploads
quarkus.http.body.delete-uploaded-files-on-end=true
```

Notas de precisão sobre os outros limites:

- `max-form-attribute-size` (2 KB) vale para **atributos de formulário** — os campos de
  texto, não a parte de arquivo, que é desviada para disco pelo `handle-file-uploads`. O
  guia do Quarkus REST tem uma frase ("the size of every part in a multipart request must
  conform to `max-form-attribute-size`") que só se aplica às partes tratadas como
  atributo. Se um campo de texto passar de 2 KB (improvável aqui: só metadados curtos),
  aí sim é preciso subir esse valor.
- `max-form-buffered-bytes` (1 KB) limita o buffer do decodificador de formulário, não o
  arquivo.
- Se algum cliente mandar a parte do vídeo com um `Content-Type` que o Quarkus não
  reconhece como arquivo, existe
  `quarkus.http.body.multipart.file-content-types` para forçar o tratamento como arquivo
  ([MultiPartConfig.java](https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/MultiPartConfig.java)):
  *"A comma-separated list of ContentType to indicate whether a given multipart field
  should be handled as a file part."*

### 1.3 Modelo de execução

Do guia oficial (seção *Execution model*): tipos de retorno `Uni`, `Multi`,
`CompletionStage` e `Publisher` rodam **non-blocking, na IO thread**; retornos diretos
rodam em worker thread. Portanto o endpoint de upload deve retornar `Uni<...>` e **não
pode** fazer `Files.readAllBytes(...)`, `putObject` síncrono ou qualquer I/O de arquivo
bloqueante — isso travaria o event loop. O caminho não-bloqueante é
`AsyncRequestBody.fromFile(Path)` (§3.1).

Uma limitação registrada no mesmo guia, relevante para o `videos`: *"For the time being,
returning Multipart data is limited to be blocking endpoints."* Isso só afeta **devolver**
`multipart/form-data`; o download do Pacote é `application/octet-stream`, então não nos
atinge.

---

## 2. Qual cliente S3 usar e como apontar para o MinIO

### 2.1 Coordenadas exatas

O `quarkus-amazon-s3` **não** é `io.quarkus`. O descritor da plataforma 3.31.3 instalado
localmente
(`~/.m2/repository/io/quarkus/platform/quarkus-amazon-services-bom-quarkus-platform-descriptor/3.31.3/…json`)
lista:

```
io.quarkiverse.amazonservices:quarkus-amazon-s3::jar:3.14.1
io.quarkiverse.amazonservices:quarkus-amazon-s3-transfer-manager::jar:3.14.1
```

e o BOM membro `io.quarkus.platform:quarkus-amazon-services-bom::pom:3.31.3`.

No `pom.xml` (o parent do projeto já importa `quarkus-bom`; basta importar o segundo BOM):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>${quarkus.platform.group-id}</groupId>
      <artifactId>quarkus-bom</artifactId>
      <version>${quarkus.platform.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>io.quarkus.platform</groupId>
      <artifactId>quarkus-amazon-services-bom</artifactId>
      <version>${quarkus.platform.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.quarkiverse.amazonservices</groupId>
    <artifactId>quarkus-amazon-s3</artifactId>
  </dependency>
  <!-- OBRIGATÓRIO para o S3AsyncClient: transporte Netty -->
  <dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>netty-nio-client</artifactId>
  </dependency>
</dependencies>
```

A doc oficial da extensão é explícita quanto a isso: *"we need to add the Netty HTTP
client dependency to the `pom.xml`"* para o cliente assíncrono, e *"You need to add to the
classpath a proper implementation of the sync client"* (`url-connection-client`) para o
síncrono. **Se só usarmos o async, só o `netty-nio-client` é necessário** — não adicionar
`url-connection-client` evita arrastar transporte que não vamos usar.

Fonte: [amazon-s3.adoc @ 3.14.1](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/docs/modules/ROOT/pages/amazon-s3.adoc),
[doc publicada](https://docs.quarkiverse.io/quarkus-amazon-services/dev/amazon-s3.html).

### 2.2 Existe variante reativa? Sim — `S3AsyncClient`

A extensão produz três beans para S3, conforme
[`S3Processor.java` @ 3.14.1](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/s3/deployment/src/main/java/io/quarkiverse/amazon/s3/deployment/S3Processor.java):

- `software.amazon.awssdk.services.s3.S3Client` (síncrono — **não usar** no `videos`)
- `software.amazon.awssdk.services.s3.S3AsyncClient` (assíncrono, `CompletableFuture`)
- `software.amazon.awssdk.services.s3.presigner.S3Presigner`

`S3AsyncClient` sobre Netty é non-blocking e integra com Mutiny via
`Uni.createFrom().completionStage(...)`. Também existe `@S3Crt S3AsyncClient` (cliente
CRT), que exige `aws-crt-client` e traz biblioteca nativa — desnecessário aqui.

### 2.3 Configuração para MinIO

```properties
quarkus.s3.endpoint-override=http://minio:9000
quarkus.s3.path-style-access=true
quarkus.s3.aws.region=us-east-1
quarkus.s3.aws.credentials.type=static
quarkus.s3.aws.credentials.static-provider.access-key-id=${MINIO_ACCESS_KEY}
quarkus.s3.aws.credentials.static-provider.secret-access-key=${MINIO_SECRET_KEY}
quarkus.s3.async-client.type=netty
```

- `endpoint-override` vem de `SdkConfig#endpointOverride`:
  *"The endpoint URI with which the SDK should communicate."*
  ([SdkConfig.java](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/common/runtime-spi/src/main/java/io/quarkiverse/amazon/common/runtime/SdkConfig.java))
- `path-style-access` vem de `S3Config#pathStyleAccess`, default `false`:
  *"Enable using path style access for accessing S3 objects instead of DNS style access."*
  ([S3Config.java](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/s3/runtime/src/main/java/io/quarkiverse/amazon/s3/runtime/S3Config.java))
  O `S3Recorder` repassa esse valor para `S3Configuration.pathStyleAccessEnabled(...)`
  tanto no cliente quanto no presigner
  ([S3Recorder.java](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/s3/runtime/src/main/java/io/quarkiverse/amazon/s3/runtime/S3Recorder.java)).

**Por que path-style é obrigatório no nosso Compose**: virtual-host style transforma
`http://minio:9000/pacotes/x.zip` em `http://pacotes.minio:9000/x.zip`. A MinIO só aceita
esse formato se `MINIO_DOMAIN` estiver configurado *e* houver DNS curinga
(`*.minio.example.net`) resolvendo para o servidor — a própria documentação afirma que
*"Setting MINIO_DOMAIN alone is not sufficient"* e exige o wildcard DNS e o SAN curinga no
certificado
([MinIO — Core Settings](https://docs.min.io/enterprise/aistor-object-store/reference/aistor-server/settings/core/)).
Nada disso existe num Compose. Logo: `path-style-access=true`.

`region` é obrigatório mesmo no MinIO porque a assinatura SigV4 inclui a região; qualquer
valor consistente serve (`us-east-1` é o padrão de fato da MinIO).

### 2.4 Timeouts a revisar para arquivos grandes

`AsyncHttpClientConfig`
([fonte](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/common/runtime-spi/src/main/java/io/quarkiverse/amazon/common/runtime/AsyncHttpClientConfig.java))
traz defaults que podem cortar transferências longas:

| Propriedade | Default |
|---|---|
| `quarkus.s3.async-client.max-concurrency` | `50` |
| `quarkus.s3.async-client.read-timeout` | `30S` |
| `quarkus.s3.async-client.write-timeout` | `30S` |
| `quarkus.s3.async-client.connection-timeout` | `10S` |
| `quarkus.s3.async-client.connection-acquisition-timeout` | `2S` |

Os timeouts de read/write são *por socket read/write*, não pela transferência inteira, então
30 s costuma bastar; mas se `quarkus.s3.api-call-timeout` for configurado, ele cobre a
chamada toda (`SdkConfig#apiCallTimeout`: *"This timeout covers the entire client execution
except for marshalling"*) — **não** configure `api-call-timeout` com valor pequeno para um
PUT de 500 MB.

---

## 3. Os quatro fluxos, em código

Os trechos abaixo são adaptações diretas do exemplo oficial `S3AsyncClientResource` da
documentação da extensão (arquivo `docs/modules/ROOT/pages/amazon-s3.adoc` @ 3.14.1).

### 3.1 `videos`: receber upload → `S3AsyncClient.putObject` (sem carregar em memória)

```java
@POST
@Path("/videos")
@Consumes(MediaType.MULTIPART_FORM_DATA)
public Uni<Response> upload(@RestForm("arquivo") FileUpload arquivo) {
    PutObjectRequest req = PutObjectRequest.builder()
            .bucket("videos")
            .key(chave)
            .contentType(arquivo.contentType())
            .build();

    return Uni.createFrom()
            .completionStage(() -> s3.putObject(req,
                    AsyncRequestBody.fromFile(arquivo.uploadedFile())))
            .replaceWith(Response.accepted().build());
}
```

`AsyncRequestBody.fromFile(Path)` lê o arquivo de forma assíncrona (o cliente HTTP se
inscreve como `Publisher<ByteBuffer>` e recebe os chunks conforme demanda), então o event
loop não é bloqueado e o arquivo inteiro nunca fica residente
([AsyncRequestBody javadoc](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/async/AsyncRequestBody.html)).

**Evitar**: `AsyncRequestBody.fromBytes(...)` (cópia integral em heap) e
`AsyncRequestBody.fromInputStream(...)` sem executor dedicado — o javadoc alerta que ele
*"is used to run a blocking task that reads from the input stream"* e que um pool
subdimensionado serializa requisições.

### 3.2 `videos`: download do Pacote por streaming (sem carregar o ZIP)

Exemplo oficial da extensão, verbatim:

```java
@GET
@Path("download/{objectKey}")
@Produces(MediaType.APPLICATION_OCTET_STREAM)
public RestMulti<Buffer> downloadFile(String objectKey) {
    return RestMulti.fromUniResponse(Uni.createFrom()
            .completionStage(() -> s3.getObject(buildGetRequest(objectKey),
                    AsyncResponseTransformer.toPublisher())),
            response -> Multi.createFrom()
                    .safePublisher(AdaptersToFlow.publisher((Publisher<ByteBuffer>) response))
                    .map(S3AsyncClientResource::toBuffer),
            response -> Map.of(
                    "Content-Disposition", List.of("attachment;filename=" + objectKey),
                    "Content-Type", List.of(response.response().contentType())));
}

private static Buffer toBuffer(ByteBuffer bytebuffer) {
    byte[] result = new byte[bytebuffer.remaining()];
    bytebuffer.get(result);
    return Buffer.buffer(result);
}
```

Por que isso não carrega o ZIP inteiro: `AsyncResponseTransformer.toPublisher()` devolve
um `Publisher<ByteBuffer>` reativo cujo `CompletableFuture` completa **quando o corpo
começa** a chegar, não quando termina; o `RestMulti` repassa os chunks para a resposta HTTP
respeitando backpressure. O javadoc alerta: *"A subscriber that never requests data stalls
the response, and requesting unbounded data can exhaust memory."* — o `RestMulti` do
Quarkus REST é o subscriber correto aqui, então não escrever `Subscriber` à mão.

`RestMulti.fromUniResponse(...)` existe exatamente porque, como diz o guia do Quarkus REST,
*"Response filters and exception mappers are skipped for streamed responses since headers
cannot be modified after streaming begins"* — os headers (`Content-Disposition`) precisam
ser calculados **antes** do primeiro chunk, e é isso que o terceiro argumento faz.

**Nunca usar** `AsyncResponseTransformer.toBytes()` neste caminho: o javadoc diz que ele
carrega a resposta inteira em memória e *"can exhaust memory for large responses"*.
`toBlockingInputStream()` também está fora — bloqueia a thread chamadora.

Alternativas de retorno documentadas no guia do Quarkus REST, caso o Pacote precise passar
por disco antes: `java.nio.file.Path`, `java.io.File`, `PathPart`, `FilePart` e
`io.vertx.core.file.AsyncFile` são tipos de retorno suportados e transmitidos em stream:

```java
@GET @Path("file")
public RestResponse<java.nio.file.Path> largePathRestResponse() {
    return RestResponse.ResponseBuilder.ok(path)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pacote.zip")
            .build();
}
```

### 3.3 `extracao`: baixar o vídeo para disco

O ffmpeg precisa de um arquivo real, então aqui o transformer certo é `toFile`:

```java
Uni.createFrom().completionStage(() ->
    s3.getObject(GetObjectRequest.builder().bucket("videos").key(chave).build(),
                 AsyncResponseTransformer.toFile(destino)));
```

Javadoc de `toFile(Path)`: escreve direto em disco, pegada de memória mínima; o
`CompletableFuture` só completa quando o corpo terminou de ser gravado; em caso de erro o
SDK tenta apagar o arquivo; **os diretórios pai precisam existir**.

### 3.4 `extracao`: subir o Pacote

Idêntico a §3.1: `AsyncRequestBody.fromFile(zipPath)`.

> Se, na prática, aparecer necessidade de multipart upload S3 (partes paralelas, retomada),
> a extensão oferece `io.quarkiverse.amazonservices:quarkus-amazon-s3-transfer-manager`
> com `S3TransferManager` injetável, que *"supports only async operations"* e compartilha a
> configuração do `S3AsyncClient`. Para o escopo do hackathon isso é otimização prematura —
> `putObject` com `fromFile` já resolve.

---

## 4. Presigned URL para o download do Pacote

### 4.1 Dá para fazer? Sim, e sem código de assinatura manual

`S3Processor` declara `presignerClientName() = S3Presigner` e `presignerBuilderClass() =
S3Presigner.Builder`, e o `S3Recorder` constrói o builder:

```java
@Override
public RuntimeValue<SdkPresigner.Builder> createPresignerBuilder() {
    S3Presigner.Builder builder = S3Presigner.builder()
            .serviceConfiguration(s3ConfigurationBuilder().build())  // inclui pathStyleAccessEnabled
            .dualstackEnabled(config.getValue().dualstack());
    return new RuntimeValue<>(builder);
}
```

E o `AmazonClientCommonRecorder` injeta região, credenciais e `endpointOverride` no
presigner
([fonte](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/common/runtime/src/main/java/io/quarkiverse/amazon/common/runtime/AmazonClientCommonRecorder.java)):

```java
public void initAwsPresigner(...) {
    namedConfig.region().or(...).ifPresent(builder::region);
    builder.credentialsProvider(credential);
}
public void initSdkPresigner(...) {
    namedConfig.endpointOverride().filter(URI::isAbsolute)
        .or(...).ifPresent(builder::endpointOverride);
}
```

Logo, **basta injetar** — a URL sai já apontando para o endpoint do MinIO e em path-style:

```java
@Inject
S3Presigner presigner;

String urlDoPacote(String chave, Duration ttl) {
    GetObjectRequest get = GetObjectRequest.builder()
            .bucket("pacotes").key(chave)
            // força o browser a baixar com nome amigável
            .responseContentDisposition("attachment; filename=\"pacote.zip\"")
            .build();
    return presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(get)
            .build()).url().toExternalForm();
}
```

(API conforme
[AWS SDK for Java 2.x — Work with S3 pre-signed URLs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html).)

O endpoint do `videos` então vira um `303 See Other`:

```java
@GET @Path("/videos/{id}/pacote")
public Uni<Response> baixarPacote(@PathParam("id") UUID id) {
    return repo.buscarDoDono(id, sub)                       // autorização acontece AQUI
        .onItem().ifNull().failWith(NotFoundException::new)
        .map(v -> Response.seeOther(URI.create(
                urlDoPacote(v.chavePacote(), Duration.ofMinutes(5)))).build());
}
```

### 4.2 Implicações de autorização — a parte que importa

A autorização é do `videos` (dono do Vídeo vem do `sub` do token, nunca do request). Com
redirect, a cadeia muda de natureza:

1. **A URL assinada é um bearer token.** Documentação da AWS, verbatim: *"The capabilities
   of a presigned URL are limited by the permissions of the user who created it. In
   essence, presigned URLs are bearer tokens that grant access to those who possess them.
   As such, we recommend that you protect them appropriately."*
   ([S3 User Guide](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)).
   Quem interceptar a URL (log de proxy, histórico de browser, `Referer`, print de tela do
   Swagger UI) baixa o Pacote **sem token do Keycloak**.
2. **A autorização é verificada uma única vez, na emissão.** Depois disso o MinIO não sabe
   nada sobre o `sub`. Se o Vídeo for apagado, ou o usuário perder acesso, a URL continua
   funcionando até expirar. A AWS documenta que *"You can use the presigned URL multiple
   times, up to the expiration date and time."*
3. **A credencial usada é a do serviço, não a do usuário.** No Compose usamos credencial
   estática do MinIO; a URL carrega o poder dessa credencial restrito àquele
   bucket+chave+método+prazo. Isso é aceitável desde que o `videos` só assine chaves cujo
   dono ele acabou de conferir.
4. **Expiração.** Máximo de 7 dias com SigV4 e credencial estática
   (*"If you use the AWS CLI or AWS SDKs, the expiration time can be set as high as 7
   days"*); a MinIO usa o mesmo default de 168 h no `mc share download`
   ([mc share download](https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-share-download.html)).
   Para nós isso é irrelevante — **o TTL correto é de minutos**. Recomendação: 5 min.
5. **Download já iniciado não é interrompido pela expiração.** *"if a client begins to
   download a large file immediately before the expiration time, the download continues
   even if the expiration time passes during the download. However, if the connection
   drops and the client tries to restart the download after the expiration time passes,
   the download fails."* — TTL curto não quebra downloads longos, só a retomada.
6. **Armadilha operacional do Compose: o host da URL precisa ser alcançável pelo cliente.**
   O `videos` fala com `http://minio:9000` (nome de serviço da rede Docker), mas o `curl`
   do avaliador roda no host, onde `minio` não resolve. A URL assinada carrega o host no
   cálculo da assinatura, então **não dá para reescrever o host depois**. Duas saídas
   corretas:
   - publicar a porta do MinIO no host e usar um endpoint que valha nos dois lados
     (ex.: adicionar `minio` ao `/etc/hosts` do avaliador — frágil), ou
   - configurar um **presigner nomeado** com endpoint público, mantendo o cliente default
     apontando para a rede interna:

     ```properties
     quarkus.s3.endpoint-override=http://minio:9000            # servidor -> MinIO
     quarkus.s3.publico.endpoint-override=http://localhost:9000 # URL entregue ao usuário
     quarkus.s3.path-style-access=true                         # global, vale para ambos
     ```

     ```java
     @Inject @AmazonClient("publico") S3Presigner presigner;
     ```

     Isso é suportado: `HasAmazonClientRuntimeConfig#clients()` é um
     `Map<String, AmazonClientConfig>` com `@WithParentName` e
     `@WithUnnamedKey("<default>")`, ou seja `quarkus.s3.<nome>.<prop>`; e
     `AmazonClientCommonRecorder#initSdkPresigner` resolve `namedConfig.endpointOverride()`
     antes do default. Atenção: `path-style-access`, `checksum-validation` etc. são
     métodos próprios de `S3Config` (não de `AmazonClientConfig`), logo são **globais** —
     `S3Recorder#createPresignerBuilder` aplica o mesmo `S3Configuration` a todos os
     presigners. O qualificador é
     `io.quarkiverse.amazon.common.AmazonClient` (`@AmazonClient("publico")`).
     Essa é a opção limpa e é a que sustenta a demo.

### 4.3 Veredito

Presigned URL **é viável e é a opção que melhor protege o event loop** (o serviço não
transporta um byte do ZIP). O custo é que a autorização deixa de ser contínua e vira um
token portador de curta duração, e que é preciso resolver o hostname público do MinIO.

Recomendação: **implementar o streaming (§3.2) como caminho padrão** — é o que mantém a
autorização inteiramente dentro do `videos`, funciona sem expor o MinIO ao host, e não
carrega o ZIP em memória — e deixar o presigned URL registrado como alternativa se o
tamanho dos Pacotes tornar o proxy caro. O streaming é também mais simples de demonstrar
com `curl -H "Authorization: Bearer ..."`, o que interessa ao roteiro do vídeo.

---

## 5. Dev Services / Testcontainers em `@QuarkusTest`

### 5.1 O Dev Service nativo da extensão é LocalStack, não MinIO

O `S3DevServicesProcessor` @ 3.14.1
([fonte](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/devservices/s3/src/main/java/io/quarkiverse/amazon/devservices/s3/S3DevServicesProcessor.java))
estende `AbstractDevServicesLocalStackProcessor` e usa
`org.testcontainers.containers.localstack.LocalStackContainer` com `Service.S3`. Ele:

- cria os buckets de `quarkus.s3.devservices.buckets` no startup (default: `default`,
  conforme
  [S3DevServicesBuildTimeConfig](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/s3/runtime/src/main/java/io/quarkiverse/amazon/s3/runtime/S3DevServicesBuildTimeConfig.java));
- força `quarkus.s3.path-style-access=true` — com o comentário no código:
  *"Localstack returns an ip as host and it confuse DefaultS3EndpointProvider ruleset"*.

Para o hackathon isso é ótimo: **o teste não precisa de MinIO**. A API S3 é a mesma, o
código sob teste é idêntico, e o Dev Service cuida do endpoint, credenciais e buckets:

```properties
# videos/src/test/resources/application.properties
quarkus.s3.devservices.buckets=videos,pacotes
```

### 5.2 Se o teste precisar ser MinIO de verdade

Duas opções, ambas de primeira mão:

**(a) Compose Dev Services do Quarkus** — o Quarkus descobre um arquivo
`compose-devservices.yml` (ou `docker-compose-devservices.[yml|yaml]`) na raiz do projeto e
sobe a stack; funciona em `@QuarkusTest`. O mapeamento de porta→propriedade é feito por
label no serviço:

```yaml
services:
  minio:
    image: minio/minio
    command: server /data
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports: [ "9000" ]
    labels:
      io.quarkus.devservices.compose.config_map.port.9000: quarkus.s3.endpoint-override
```

Propriedades: `quarkus.compose.devservices.enabled` (default `true`),
`quarkus.compose.devservices.files`, `quarkus.compose.devservices.project-name`,
`quarkus.compose.devservices.profiles`,
`quarkus.compose.devservices.reuse-project-for-tests`.
A extensão S3 já tem teste cobrindo esse cenário
([DevServicesWithComposeDevServicesTest](https://github.com/quarkiverse/quarkus-amazon-services/blob/3.14.1/s3/deployment/src/test/java/io/quarkiverse/amazon/s3/deployment/DevServicesWithComposeDevServicesTest.java)).
Fonte: [Quarkus — Compose Dev Services](https://quarkus.io/version/3.31/guides/compose-dev-services).

**(b) `MinIOContainer` do Testcontainers** dentro de um
`QuarkusTestResourceLifecycleManager`:

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>minio</artifactId>
  <scope>test</scope>
</dependency>
```

```java
MinIOContainer container = new MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z");
// container.getS3URL(), container.getUserName(), container.getPassword()
```

devolvendo do `start()` o mapa:
`quarkus.s3.endpoint-override`, `quarkus.s3.aws.credentials.static-provider.access-key-id`,
`…secret-access-key`, `quarkus.s3.path-style-access=true`.
Fonte: [Testcontainers — MinIO Module](https://java.testcontainers.org/modules/minio/).

**Recomendação**: usar (a) Dev Services LocalStack para os testes por serviço (rápido, zero
código) e deixar MinIO só no Compose de runtime e no script de smoke ponta-a-ponta. Isso é
consistente com a decisão do mapa de não automatizar E2E no CI.

---

## 6. Riscos e pontos que ainda precisam de decisão

1. **Teto de upload** (`quarkus.http.limits.max-body-size`) — entra em "Limites
   operacionais" do mapa. Sugestão: 1 GB, com mensagem de erro clara no 413.
2. **Volume para `uploads-directory`** no Compose do `videos` — sem isso, o disco do
   contêiner enche.
3. **Nome de bucket e formato da chave de objeto** — os serviços trocam chaves; isso é
   contrato e deveria fechar junto com o ticket 007 (contrato de mensagens).
4. **Endpoint público do MinIO** — só é problema se o presigned URL for adotado (§4.2.6).
5. **Retenção dos objetos** — o Vídeo original continua no MinIO depois do Pacote pronto?
   Fica em aberto no mapa.

---

## Fontes primárias

- Quarkus REST guide (3.31): <https://quarkus.io/version/3.31/guides/rest>
- Quarkus Compose Dev Services: <https://quarkus.io/version/3.31/guides/compose-dev-services>
- `BodyConfig.java` @ 3.31.3: <https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/BodyConfig.java>
- `ServerLimitsConfig.java` @ 3.31.3: <https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/ServerLimitsConfig.java>
- `MultiPartConfig.java` @ 3.31.3: <https://github.com/quarkusio/quarkus/blob/3.31.3/extensions/vertx-http/runtime/src/main/java/io/quarkus/vertx/http/runtime/MultiPartConfig.java>
- Quarkiverse Amazon S3 (doc): <https://docs.quarkiverse.io/quarkus-amazon-services/dev/amazon-s3.html>
- Quarkiverse Amazon Services @ 3.14.1 (código): <https://github.com/quarkiverse/quarkus-amazon-services/tree/3.14.1>
- Descritor da plataforma 3.31.3 (local): `~/.m2/repository/io/quarkus/platform/quarkus-amazon-services-bom-quarkus-platform-descriptor/3.31.3/`
- AWS SDK for Java 2.x — pre-signed URLs: <https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html>
- AWS SDK for Java 2.x — `AsyncResponseTransformer`: <https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/async/AsyncResponseTransformer.html>
- AWS SDK for Java 2.x — `AsyncRequestBody`: <https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/core/async/AsyncRequestBody.html>
- Amazon S3 User Guide — presigned URLs: <https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html>
- MinIO — Core Settings (`MINIO_DOMAIN`): <https://docs.min.io/enterprise/aistor-object-store/reference/aistor-server/settings/core/>
- MinIO — `mc share download`: <https://docs.min.io/community/minio-object-store/reference/minio-mc/mc-share-download.html>
- Testcontainers — MinIO Module: <https://java.testcontainers.org/modules/minio/>
