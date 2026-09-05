# Preservar mensagens ao recriar o RabbitMQ

- id: 044
- label: wayfinder:bug
- status: fechado
- assignee: Codex
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `3a3ec95...270b891`, convertido em ticket com
aprovação do usuário. A configuração do broker monta apenas arquivos de configuração,
sem volume nomeado de dados nem identidade estável do nó. Ao derrubar e subir novamente
a stack, mensagens pendentes podem desaparecer enquanto o Postgres conserva as marcas
de publicação. A reconciliação do ADR 0003 não recupera comandos já marcados como publicados.
O cenário foi identificado por revisão estática, ainda sem reprodução em execução.

## O que entregar

Comandos de Extração já confirmados pelo broker sobrevivem à recriação da stack com
preservação dos dados, e os Vídeos correspondentes chegam a um estado terminal após a
retomada dos workers. A persistência do RabbitMQ deve conservar também sua identidade de nó.
Isso atende aos requisitos de persistir dados e não perder requisições, sem mudar a
política de entregas do ADR 0001 nem a reconciliação do ADR 0003.

## Condições de aceite

- [x] Preparar mensagens pendentes com publicação confirmada e marca gravada no Postgres;
  registrar o comportamento anterior à correção.
- [x] Derrubar e subir a stack sem excluir seus volumes e comprovar que as mensagens
  pendentes continuam disponíveis ao broker recriado.
- [x] Retomar os workers e verificar pela API que todos os Vídeos do ensaio chegam a
  estado terminal, sem apagar marcas nem republicar manualmente os comandos.
- [x] Manter as filas, DLQs e políticas de entrega previstas no contrato de mensagens.
- [x] Documentar a persistência e a diferença entre recriar a stack preservando dados e
  excluir deliberadamente os volumes; registrar a evidência do ensaio e do smoke ponta a ponta.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

O Compose monta `fiapx-rabbitmq-data:/var/lib/rabbitmq` e fixa `hostname: rabbitmq`.
O nó passa a se chamar `rabbit@rabbitmq` em toda recriação dentro do mesmo projeto.
Filas, DLQs, bindings, publicadores e políticas de entrega não foram alterados.
O Compose também monta `fiapx-keycloak-data:/opt/keycloak/data`: no ensaio, somente
persistir o RabbitMQ preservou os três comandos e produziu três `CONCLUIDO`, mas a API
respondeu `404` porque o Keycloak efêmero reimportou `demo` com outro `sub`. Persistir o
realm é necessário para cumprir o aceite de consultar os mesmos Vídeos pela API após
recriar a stack inteira. Isso mantém o `start-dev` da demo, sem trocar seu banco nem
alterar a política OIDC. O README também registra a migração inicial e o fato de que um
realm persistido não é reimportado automaticamente.
O README explica `down` versus `down -v`, o vínculo dos volumes ao projeto Compose e
como atualizar uma instalação antiga: drenar as filas antes de trocar o broker, pois o
volume nomeado não importa o volume anônimo anterior.

### Ensaio reproduzível

`./scripts/persistencia-rabbitmq.sh` usa o projeto isolado `fiapx-persistencia` e portas
próprias. Sobe a topologia pelos serviços reais, para os workers e envia três fixtures pela
API. Antes de derrubar, exige três mensagens prontas, nenhuma em voo, nenhum consumidor e
três marcas de publicação preenchidas. Essas marcas são gravadas após publisher confirms
pelo fluxo de produção; o script não publica diretamente no broker nem escreve no banco.

Após `down` sem `-v`, sobe **somente Postgres e RabbitMQ**: nenhum serviço pode republicar
ou redeclarar a topologia para mascarar perda. Exige outro container, os três comandos,
a mesma identidade de nó e snapshots idênticos de filas (incluindo argumentos e DLQs),
exchanges da aplicação, bindings, políticas e marcas. O exchange interno
`amq.rabbitmq.log` fica fora da comparação: ele apareceu somente após o primeiro reinício
na medição, sem alteração na topologia da aplicação. Depois retoma os serviços e exige `CONCLUIDO`
para os três IDs pela API, com as marcas originais. O fixture válido torna esse critério
mais forte que apenas qualquer estado terminal. Ao final executa `scripts/smoke.sh` na
mesma stack isolada.

### Evidência anterior à correção — 2026-09-05

A execução anterior perdeu a fila: depois de `down`/`up`, a API de management respondeu
`404` para `extracao.extrair`. O nome do nó mudou de `rabbit@665ffa3872ea` para
`rabbit@ec6920fe7017`. Os três Vídeos permaneceram `RECEBIDO` e suas marcas sobreviveram
inalteradas no Postgres:

| Vídeo | `comando_publicado_em` (UTC) |
|---|---|
| `1adac53d-2c9c-4a63-90f2-52445710bb31` | `2026-09-05 22:00:08.418408+00` |
| `277f4868-c776-4d91-9a7d-b6723245d7df` | `2026-09-05 22:00:08.514208+00` |
| `e4aae1dd-1617-4c20-aceb-0de561c408e5` | `2026-09-05 22:00:08.545818+00` |

Antes da recriação, a fila tinha exatamente três mensagens prontas e zero em voo.
A execução terminou com código 1: `FALHOU: timeout: fila_pendente`.
A varredura do ADR 0003 não reconsidera essas marcas preenchidas.

### Validação da aplicação

`./mvnw test -Dquarkus.http.test-port=0`, a partir da raiz, passou: 118 testes no
`videos`, 268 no `extracao` e 24 no `notificacao`, sem falhas nem testes ignorados.
A porta aleatória evita colisão com o Keycloak da demo existente. Também passaram
`docker compose config --quiet`, `bash -n scripts/persistencia-rabbitmq.sh` e
`git diff --check`.

O ensaio Compose usa as imagens locais já disponíveis, sem reconstruir os serviços
(Java não mudou). Identificadores das imagens usadas antes e depois:

| Serviço | Image ID (`sha256`) |
|---|---|
| `videos` | `c71f28f6be07dee0ab9ee8d22ad0f0ad1b06fce3a59e51d28e9e6274f0e2c00a` |
| `extracao` | `6a0b3b01c31da332a5b185672c2c36c1e361f61de85f753bbf9a82d75fcc1dd1` |
| `notificacao` | `6aa72a6b18cf8a97d1c4e8ecee960bd31403d7341a4795b0ee2f9a5d766034bb` |

### Evidência após a correção — 2026-09-05

O mesmo ensaio terminou com código 0. Outro container RabbitMQ subiu com o mesmo nome
`rabbit@rabbitmq`, três mensagens prontas e nenhuma em voo, ainda sem os serviços de
negócio ligados. Os snapshots da topologia e das marcas foram idênticos. Depois da
retomada, o token renovado de `demo` consultou pela API:

| Vídeo | Marca original (UTC), preservada até o fim | Estado pela API |
|---|---|---|
| `2a6ed837-5cf0-4796-a6d2-340cff721f7f` | `2026-09-05 22:10:20.902428+00` | `CONCLUIDO` |
| `8690d79c-fb4c-44d3-a65b-9a1faae63dc1` | `2026-09-05 22:10:20.930518+00` | `CONCLUIDO` |
| `ff8b0cba-bff2-482a-88b0-ec429e0a2b1e` | `2026-09-05 22:10:20.850787+00` | `CONCLUIDO` |

Não houve exclusão de marcas nem publicação manual. O smoke executado em seguida passou
pelos nove passos: autenticação, envio, conclusão, ZIP íntegro com três frames (35.067
bytes), falha `ARQUIVO_INVALIDO`, resposta `409`, e-mail correlacionado e `404` para outro
dono. IDs do smoke: `1875e9df-b9d9-4839-8bd3-c502ba9af391` (concluído) e
`5ecd063f-5d36-4b9a-8d56-c1722f13ef5e` (falho).

A demo preexistente não foi recriada. A stack isolada e seus volumes ficam disponíveis
para inspeção; os Vídeos perdidos nas rodadas anteriores permanecem como evidência, sem
reparo manual. A correção preserva novas publicações nos volumes configurados; não
recupera comandos já perdidos pelo broker antigo.
