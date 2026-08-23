# FIAP X — processamento de vídeos

Envie um vídeo, receba um `.zip` com um frame por segundo.

Três serviços Quarkus em Clean Architecture, um repositório, um build Maven, orquestrados
por Docker Compose. `videos` é a borda pública e dona do estado; `extracao` e `notificacao`
são workers que só falam por mensagem.

```
            POST /videos                  ExtrairVideo                ffmpeg -vf fps=1
  usuário ───────────────► videos ──────────────────────► extracao ─────────────────► .zip
                             │  ▲                             │
                             │  └──── ExtracaoConcluida ──────┘
                             │        ExtracaoFalhou
                             │
                             └──── VideoFalhou ────► notificacao ────► e-mail
```

| Serviço | Papel | Banco | Borda HTTP |
|---|---|---|---|
| `videos` | recebe o upload, guarda o estado, entrega o Pacote | Postgres | pública, sob OIDC |
| `extracao` | baixa o vídeo, roda `ffmpeg`, sobe o `.zip` | — | — |
| `notificacao` | traduz o código da falha e manda o e-mail | — | — |

Infraestrutura: **Postgres** (estado), **RabbitMQ** (mensagens, filas quorum com DLQ),
**MinIO** (vídeos e pacotes), **Keycloak** (autenticação), **MailHog** (SMTP de demo).

## Subir a demo

Só precisa de Docker. As três imagens vêm prontas do GHCR (`amd64` e `arm64`, então Apple
Silicon roda nativo) — não há `build:` no Compose.

```bash
docker compose pull
docker compose up -d
```

O `up` respeita a ordem de saúde: os três serviços de negócio só sobem depois que Postgres,
RabbitMQ e Keycloak estão saudáveis e o seed do MinIO terminou. Cerca de um minuto no total.

```bash
docker compose ps           # todos devem ficar (healthy)
```

### O caminho inteiro em um comando

```bash
./scripts/smoke.sh          # sobe o Compose se preciso e verifica tudo, ~1 min do zero
```

O script é a verificação ponta-a-ponta e o roteiro da demo na mesma peça: sobe a stack, pega
um token, envia o vídeo de fixture, espera `CONCLUIDO`, baixa e valida o ZIP, força uma falha
até o e-mail no MailHog e confere que o Vídeo de um usuário responde `404` para o outro. Cada
passo é conferido — estado errado, status HTTP errado ou ZIP corrompido param o script no
ponto exato. Precisa de `jq` e `unzip` além do Docker.

A seção [Usar](#usar) é o mesmo percurso passo a passo, para quem quiser conduzir na mão.

| Console | Endereço | Credenciais |
|---|---|---|
| **Swagger UI** (a demo) | http://localhost:8080/q/swagger-ui | `demo` / `demo` |
| Keycloak | http://localhost:8081 | `admin` / `admin` |
| RabbitMQ | http://localhost:15672 | `fiapx` / `fiapx` |
| MinIO | http://localhost:9001 | `minioadmin` / `minioadmin` |
| MailHog | http://localhost:8025 | — |

Para derrubar: `docker compose down`, ou `docker compose down -v` para apagar também os
volumes (banco, buckets e uploads).

## Usar

Não há interface web: a demo é o **Swagger UI**. Clique em **Authorize**, entre com
`demo`/`demo` e as quatro operações passam a rodar autenticadas na própria página.

O realm traz dois usuários, `demo`/`demo` e `outro`/`outro` — o segundo existe para mostrar
que o Vídeo de um usuário responde `404` para o outro. O dono vem sempre do `sub` do token,
nunca do request.

### Pelo `curl`

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/realms/fiapx/protocol/openid-connect/token \
  -d grant_type=password -d client_id=fiapx-videos \
  -d username=demo -d password=demo | jq -r .access_token)
```

Enviar um vídeo. **O `;type=video/mp4` não é opcional**: sem ele o `curl` manda
`application/octet-stream` e a borda responde `415`.

```bash
curl -X POST http://localhost:8080/videos \
  -H "Authorization: Bearer $TOKEN" \
  -F "arquivo=@meu-video.mp4;type=video/mp4"
```

A resposta é `202 Accepted` com o Vídeo em `RECEBIDO` e o `Location` do recurso — o
processamento é assíncrono, então acompanhe o estado:

```bash
ID=<id devolvido acima>
curl -s http://localhost:8080/videos/$ID -H "Authorization: Bearer $TOKEN"   # um Vídeo
curl -s http://localhost:8080/videos     -H "Authorization: Bearer $TOKEN"   # a lista
```

`RECEBIDO` → `PROCESSANDO` → `CONCLUIDO` ou `FALHOU`. Em `CONCLUIDO`, baixe o Pacote:

```bash
curl -o pacote.zip http://localhost:8080/videos/$ID/pacote -H "Authorization: Bearer $TOKEN"
```

Antes disso o download responde `409` (*ainda não*); passados os 7 dias de retenção do
MinIO, `410` (*não mais*).

### O caminho de falha

Mande qualquer arquivo que não seja um vídeo decodificável com o nome trocado para `.mp4`:

```bash
head -c 2000 /dev/urandom > quebrado.mp4
curl -X POST http://localhost:8080/videos -H "Authorization: Bearer $TOKEN" \
  -F "arquivo=@quebrado.mp4;type=video/mp4"
```

O Vídeo vai para `FALHOU` com `motivo: ARQUIVO_INVALIDO`, e o e-mail aparece no MailHog em
http://localhost:8025. A borda valida extensão e content-type de forma **declarativa**; a
prova de que o arquivo é vídeo mora no `extracao`, que a obtém do `ffmpeg`.

### Limites

Upload até **200 MB** e vídeo até **20 minutos**. A duração é cobrada no `extracao`, onde o
`ffprobe` já roda — então um vídeo longo demais é aceito com `202` e só depois vira `FALHOU`
com `DURACAO_EXCEDIDA`, por e-mail. Formatos: `mp4`, `avi`, `mov`, `mkv`, `webm`. O Pacote
expira em 7 dias por regra de ciclo de vida do MinIO.

## Desenvolver

```bash
./mvnw verify        # a partir da raiz, sempre
```

Precisa de **Docker de pé** (os testes sobem Dev Services de Postgres, RabbitMQ, Keycloak e
S3) e de **`ffmpeg`/`ffprobe` no `PATH`** — o `extracao` chama o binário de verdade também
em teste, sem dublê.

Antes de escrever a primeira classe, leia [`AGENTS.md`](AGENTS.md): as regras de camada não
são convenção, são verificadas por `ArchitectureConstraintsTest` e reprovam o build.

O CI roda o mesmo `verify` num job só e publica as três imagens no GHCR a partir da `main`.

## Mapa do repositório

| O que | Onde |
|---|---|
| Regras de trabalho no repo, layout, branches | [`AGENTS.md`](AGENTS.md) |
| Glossário do domínio | [`CONTEXT.md`](CONTEXT.md) |
| Contrato HTTP do `videos` | [`docs/contratos/http-videos.md`](docs/contratos/http-videos.md) |
| Contrato de mensagens entre os três | [`docs/contratos/mensagens.md`](docs/contratos/mensagens.md) |
| Script de criação do banco | [`docker/postgres/init.sql`](docker/postgres/init.sql) |
| Decisões de arquitetura | [`docs/adr/`](docs/adr/) — falhas, máquina de estados, reconciliação |
| Medições que sustentam as escolhas | [`docs/pesquisa/`](docs/pesquisa/) — ffmpeg, MinIO, RabbitMQ, OIDC |
| Como o projeto foi planejado, decisão a decisão | [`docs/wayfinder/map.md`](docs/wayfinder/map.md) |
| Enunciado do hackathon | [`docs/enunciado.md`](docs/enunciado.md) |
