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
  Nada de `toBytes`/`fromBytes` (ticket 005). `max-body-size` no valor que o 011 fixou.
- Download em streaming: `RestMulti.fromUniResponse` + `toPublisher()`.
- Persistência do Vídeo conforme o modelo e o script do ticket 009, incluindo e-mail do
  dono e nome do arquivo original — o `VideoFalhou` depende dos dois.
- Os `ExceptionMapper` de problem+json, as seis situações da tabela do contrato.
- `@SecurityScheme` oauth2 com fluxo `password`, URL do realm **configurável** (o Dev
  Services sorteia porta em teste), e as anotações OpenAPI do contrato.
- Cucumber pela borda HTTP; `ArchitectureConstraintsTest` do módulo passando.

Fora deste ticket: publicar `ExtrairVideo`, consumir os eventos de progresso, publicar
`VideoFalhou` — tudo isso é o 017. Aqui o Vídeo entra e fica em `RECEBIDO`.
