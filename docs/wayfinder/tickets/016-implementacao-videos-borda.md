# Implementação do serviço videos: borda HTTP e persistência

- id: 016
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 009, 011

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
  dono e nome do arquivo original — o `VideoFalhou` depende dos dois.
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

Fora deste ticket: publicar `ExtrairVideo`, consumir os eventos de progresso, publicar
`VideoFalhou` — tudo isso é o 017. Aqui o Vídeo entra e fica em `RECEBIDO`.
