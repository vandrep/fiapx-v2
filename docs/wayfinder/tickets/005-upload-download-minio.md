# Upload multipart reativo e cliente MinIO/S3

- id: 005
- label: wayfinder:research
- status: fechado
- assignee: agente de pesquisa (sessao de 2026-08-20)
- bloqueado-por:

## Question

`videos` recebe o upload do vídeo e entrega o Pacote; `extracao` baixa o vídeo e sobe o
Pacote. Tudo isso atravessa o MinIO, e o template é **reativo** (`quarkus-rest`,
Hibernate Reactive) — o que restringe as opções.

Investigar, contra a documentação oficial do Quarkus (3.31.3):

- Upload de arquivo grande em `quarkus-rest` reativo: `@RestForm`/`FileUpload`,
  streaming vs buffer em memória, limites configuráveis, e o que acontece com um vídeo de
  centenas de MB.
- Qual cliente S3 usar (`quarkus-amazon-s3`): tem variante reativa/async? Como apontar
  para MinIO (endpoint override, path-style access)?
- **Presigned URL**: dá para o download do Pacote ser um redirect para uma URL assinada do
  MinIO em vez de streamar pelo serviço? Quais as implicações de segurança dado que a
  autorização é do `videos`?
- Como fazer streaming de download sem carregar o ZIP inteiro na memória, se o redirect
  não servir.
- Dev Services / Testcontainers para MinIO ou LocalStack em `@QuarkusTest`.

Registre os achados em `docs/pesquisa/upload-download-minio.md`, com links para as fontes
primárias.

## Resolução

Achados completos em [`docs/pesquisa/upload-download-minio.md`](../../pesquisa/upload-download-minio.md),
verificados contra o código-fonte das versões exatas (Quarkus 3.31.3, `quarkus-amazon-s3`
3.14.1 alinhado pelo descritor da plataforma) e docs oficiais AWS/MinIO/Testcontainers.

- **Achado que quebra o projeto se ignorado**: `quarkus.http.limits.max-body-size` tem
  default de **10 MB** — vídeo de centenas de MB retorna 413. O `FileUpload` em si não é
  problema (`quarkus.http.body.handle-file-uploads=true` grava em disco via Vert.x), mas
  `delete-uploaded-files-on-end=true` (default) obriga o `putObject` a completar antes da
  resposta.
- **Evitar memória**: upload → `AsyncRequestBody.fromFile(FileUpload.uploadedFile())`;
  download → `RestMulti.fromUniResponse(...)` + `AsyncResponseTransformer.toPublisher()`;
  no `extracao` → `toFile(Path)`. Proibidos: `toBytes()`, `toBlockingInputStream()`,
  `fromBytes()`.
- **Presigned URL é viável sem assinatura manual**: `S3Processor` declara `S3Presigner`
  como bean injetável, já com região, credenciais e `endpoint-override` aplicados; o
  endpoint responde `303 See Other`.
- **Implicação de autorização**: a AWS documenta presigned URL como *bearer token* — a
  posse do Vídeo é conferida **uma vez, na emissão**; depois o MinIO não conhece o usuário
  e a URL vale, reutilizável, até expirar (TTL sugerido 5 min; máximo SigV4 é 7 dias).
- **Armadilha do Compose**: o host entra na assinatura, então `http://minio:9000` não
  funciona no `curl` do avaliador. Contorno verificado: presigner nomeado
  (`quarkus.s3.publico.endpoint-override` + `@AmazonClient("publico")`), mantendo o cliente
  default na rede interna.
- **Recomendação da pesquisa**: streaming como caminho padrão (autorização contínua no
  `videos`, não expõe o MinIO ao host), com presigned URL registrada como alternativa.
- **Dependências**: `io.quarkiverse.amazonservices:quarkus-amazon-s3` via BOM
  `io.quarkus.platform:quarkus-amazon-services-bom:3.31.3` + `software.amazon.awssdk:netty-nio-client`
  (obrigatório para `S3AsyncClient`); `url-connection-client` é dispensável no caminho async.
- **Testes**: o Dev Service da extensão sobe **LocalStack**, não MinIO
  (`quarkus.s3.devservices.buckets`, path-style forçado). Para MinIO real: Compose Dev
  Services do Quarkus ou `MinIOContainer`.

A decisão entre streaming e presigned URL fica para o ticket do contrato HTTP — a pesquisa
recomenda, mas não decide.
