# Implementação do serviço videos: borda HTTP e persistência

- id: 016
- label: wayfinder:task
- status: aberto
- assignee:
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
