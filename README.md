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

### Mais de um vídeo ao mesmo tempo

O `extracao` sobe com **duas réplicas** nesta stack — elas competem pela mesma fila, e cada
uma pega uma extração por vez. Não é preciso ligar nada:

```bash
docker compose ps extracao   # duas linhas, ambas (healthy)
./scripts/concorrencia.sh    # rajada de 8 vídeos, ~1 min do zero
```

O script envia a rajada autenticada e acompanha a listagem `GET /videos`, desenhando a linha
do tempo de cada Vídeo em `PROCESSANDO` — que é exatamente o intervalo da extração, porque o
estado abre no início e fecha na conclusão. Barras que se sobrepõem são extrações
simultâneas, e ele reprova se nunca houver duas ao mesmo tempo por mais de um instante:

```
    302aa8a5           #######  CONCLUIDO (0,7s)
    d4b25402           #######  CONCLUIDO (0,7s)
```

No fim ele confere que cada Vídeo chegou a `CONCLUIDO` com o Pacote íntegro e invisível para
o outro usuário: concorrência que troca resultado não conta. Precisa de `jq` e `unzip`, como
o smoke. Para acompanhar por dentro, `docker compose logs -f extracao` mostra as duas
réplicas trabalhando ao mesmo tempo.

Para ver mais em paralelo, escale o worker — nada mais precisa mudar, porque ele não publica
porta nem guarda estado:

```bash
FIAPX_EXTRACAO_REPLICAS=4 ./scripts/concorrencia.sh 16
```

A variável é a mesma do `docker-compose.yml` (`replicas: ${FIAPX_EXTRACAO_REPLICAS:-2}`),
então vale igual para um `docker compose up -d` avulso. Prefira-a a `--scale`: o script roda
`up -d`, que devolveria a stack ao valor do arquivo.

Quanto isso rende foi medido até 6 réplicas (eficiência de escala 0,88; 15,6 vídeo/min) em
[`docs/pesquisa/carga-escalabilidade.md`](docs/pesquisa/carga-escalabilidade.md).

| Console | Endereço | Credenciais |
|---|---|---|
| **Swagger UI** (a demo) | http://localhost:8080/q/swagger-ui | `demo` / `demo` |
| Keycloak | http://localhost:8081 | `admin` / `admin` |
| RabbitMQ | http://localhost:15672 | `fiapx` / `fiapx` |
| MinIO | http://localhost:9001 | `minioadmin` / `minioadmin` |
| MailHog | http://localhost:8025 | — |

Para derrubar preservando os dados: `docker compose down`. O próximo `docker compose up -d`
reutiliza os volumes do mesmo projeto Compose: banco, buckets, uploads, mensagens do
RabbitMQ e dados do Keycloak. O broker usa o volume `fiapx-rabbitmq-data` em
`/var/lib/rabbitmq` e o hostname fixo `rabbitmq`, mantendo a identidade `rabbit@rabbitmq` ao recriar o container.
Mantenha também o nome do projeto Compose: outro `-p` seleciona outros volumes.
O volume `fiapx-keycloak-data` preserva as identidades dos usuários: sem ele, o realm
reimportado gera outro `sub` para `demo`, que deixa de enxergar os Vídeos enviados antes.
Com dados existentes, o Keycloak não reimporta o realm; alterações em `realm-export.json`
precisam ser aplicadas ao realm existente ou testadas em um projeto novo.

`docker compose down -v` exclui deliberadamente os volumes, inclusive as mensagens
pendentes. É um reset dos dados, não um procedimento de reinício.

Para atualizar uma stack criada antes do ticket 044, deixe as filas drenarem antes de
recriar o broker. O novo volume não importa o conteúdo do volume anônimo antigo, e mudar
a identidade do nó não migra esse conteúdo. Confira filas de trabalho, DLQs e
Estacionamento no management UI antes da atualização.
A instalação antiga também não migra automaticamente os dados efêmeros do Keycloak;
preserve ou exporte seu realm com as identidades antes de recriá-lo se precisar manter
acesso aos Vídeos existentes. A garantia de recriação vale para dados gravados já nos
volumes nomeados.

O ensaio `./scripts/persistencia-rabbitmq.sh` publica três Vídeos, verifica comandos
pendentes e marcas no Postgres, executa `down`/`up` sem excluir volumes, compara a
topologia e acompanha os mesmos Vídeos até `CONCLUIDO` pela API. Ao final roda o smoke.
Precisa de Docker Compose com suporte a `!override`, `curl`, `jq`, `diff`, `grep` e `unzip`.
Usa o projeto separado `fiapx-persistencia`, com portas 18080, 18081, 25672, 19001 e 18025;
deixa seus containers e volumes para inspeção. Para encerrá-lo preservando os dados:
`docker compose -p fiapx-persistencia down`.

## Usar

Não há interface web: a demo é o **Swagger UI**. Clique em **Authorize**, entre com
`demo`/`demo` e as quatro operações passam a rodar autenticadas na própria página.

O diálogo Authorize some com `client_id`, `client_secret` e o seletor "Client credentials
location" — CSS de demo, não indisponibilidade: neste client público só há uma resposta
certa para os três, e deixá-los visíveis só convida a preencher errado antes de digitar
`demo`/`demo`.

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

### Ferramental de agente versionado

`.claude/skills/` e `.devcontainer/` estão no repositório de entrega, não num
`.gitignore`, porque fazem parte de como este projeto foi construído: as skills
automatizam o fluxo de tickets em [`docs/wayfinder/`](docs/wayfinder/map.md), e o
devcontainer fixa o toolchain (Java, Node, Docker rootless) que qualquer clone precisa
para reproduzir `./mvnw verify` sem depender do que já está instalado em quem entrega ou
revisa. Nenhum dos dois é pedido pelo enunciado; versionar os dois é tratar o processo de
construção como parte reproduzível da entrega, não como andaime descartado.

### Devcontainer com Docker rootless

O devcontainer suporta Docker rootless em host Linux. Antes de reconstruí-lo,
`XDG_RUNTIME_DIR` precisa apontar para o diretório de runtime do usuário que executa o daemon
(normalmente `/run/user/$(id -u)`) e o socket precisa existir em
`$XDG_RUNTIME_DIR/docker.sock`. A configuração usa esse valor tanto no bind mount quanto no
endereço que o Ryuk enxerga; portanto, não pressupõe UID 1000.

O container usa a rede do host e anuncia `127.0.0.1` ao Testcontainers. Essas duas opções são
necessárias para que os testes alcancem as portas publicadas pelo daemon rootless. Depois de
alterar a configuração, use **Rebuild Container** no editor antes de executar `./mvnw test`.

## Mapa do repositório

| O que | Onde |
|---|---|
| Visão de conjunto: por que três serviços, como escala, o que foi recusado | [`docs/arquitetura.md`](docs/arquitetura.md) |
| Regras de trabalho no repo, layout, branches | [`AGENTS.md`](AGENTS.md) |
| Glossário do domínio | [`CONTEXT.md`](CONTEXT.md) |
| Contrato HTTP do `videos` | [`docs/contratos/http-videos.md`](docs/contratos/http-videos.md) |
| Contrato de mensagens entre os três | [`docs/contratos/mensagens.md`](docs/contratos/mensagens.md) |
| Script de criação do banco | [`docker/postgres/init.sql`](docker/postgres/init.sql) |
| Decisões de arquitetura | [`docs/adr/`](docs/adr/) — falhas, máquina de estados, reconciliação |
| Medições que sustentam as escolhas | [`docs/pesquisa/`](docs/pesquisa/) — ffmpeg, MinIO, RabbitMQ, OIDC |
| Como o projeto foi planejado, decisão a decisão | [`docs/wayfinder/map.md`](docs/wayfinder/map.md) |
| Roteiro do vídeo de apresentação | [`docs/roteiro-video.md`](docs/roteiro-video.md) |
| Enunciado do hackathon | [`docs/enunciado.md`](docs/enunciado.md) |
