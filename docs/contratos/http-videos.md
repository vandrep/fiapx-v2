# Contrato HTTP do serviço `videos`

`videos` é a única borda pública do sistema. Sem interface web, **este contrato é o
produto**: é o que a banca vê no Swagger UI e o que o script de smoke exercita.

Vocabulário em [`CONTEXT.md`](../../CONTEXT.md). Fronteira entre serviços em
[`mensagens.md`](mensagens.md). Decidido no
[ticket 008](../wayfinder/tickets/008-contrato-http-videos.md).

## Princípios

- **A URL fala a língua do domínio.** O glossário é em português; a borda também. Um
  domínio em português com endpoints em inglês obriga todo leitor a traduzir duas vezes.
- **O dono vem do token, nunca do request.** O escopo de toda operação é o `sub` do bearer
  token. Não existe parâmetro de usuário em lugar nenhum deste contrato.
- **Uma representação de Vídeo, não três.** O corpo do `POST`, o do `GET` individual e cada
  item da listagem são o mesmo objeto.
- **Chave de objeto não vaza.** `chaveVideo` e `chaveDestinoPacote` são detalhe interno; o
  cliente chega ao Pacote por sub-recurso, não por URL de MinIO.

## Endpoints

| Verbo | Path | O que faz |
|---|---|---|
| `POST` | `/videos` | envia o Vídeo |
| `GET` | `/videos` | lista os Vídeos do usuário |
| `GET` | `/videos/{id}` | consulta um Vídeo |
| `GET` | `/videos/{id}/pacote` | baixa o Pacote |

O Pacote é **sub-recurso do Vídeo**, e não `/pacotes/{id}`, porque o glossário diz que um
Vídeo `CONCLUIDO` produziu exatamente um Pacote — ele não tem identidade própria.

Não há `DELETE`: o enunciado não pede, e retenção é assunto do ticket 011.

## Representação de Vídeo

```json
{
  "id": "3f2a8c14-9b7e-4d0a-a1c3-6e5f2b8d4a09",
  "nome": "ferias.mp4",
  "estado": "CONCLUIDO",
  "tamanhoBytes": 48213004,
  "recebidoEm": "2026-08-21T14:03:11Z",
  "concluidoEm": "2026-08-21T14:05:47Z",
  "motivo": null
}
```

| Campo | Tipo | Nota |
|---|---|---|
| `id` | UUID | o mesmo `idVideo` que correlaciona todas as mensagens |
| `nome` | string | nome de arquivo original; sem ele a listagem é uma coluna de UUIDs |
| `estado` | enum | `RECEBIDO` \| `PROCESSANDO` \| `CONCLUIDO` \| `FALHOU` |
| `tamanhoBytes` | inteiro | |
| `recebidoEm` | instante | ISO-8601 UTC |
| `concluidoEm` | instante \| null | preenchido em `CONCLUIDO` e `FALHOU`; `null` antes |
| `motivo` | enum \| null | **só o código**, `null` fora de `FALHOU` — ver abaixo |

Não existe campo `urlPacote`: a URL é derivável do `id`, e um cliente que a monta sozinho
não fica mais acoplado do que já está.

### Por que `motivo` é código e não frase

O contrato de mensagens estabelece que o texto humano do motivo pertence ao `notificacao`,
que é quem conhece o contexto de e-mail. Se esta API devolvesse uma frase, o `videos`
passaria a manter uma **segunda** tradução do mesmo enum, e as duas divergiriam.

Então o campo carrega o código cru — `ARQUIVO_INVALIDO`, `FORMATO_NAO_SUPORTADO`,
`SEM_FLUXO_DE_VIDEO`, `DURACAO_EXCEDIDA`, `TENTATIVAS_ESGOTADAS`, `DESCONHECIDO` — declarado como enum no
OpenAPI, que é onde o significado de cada um fica documentado.

`DESCONHECIDO` não é publicado por ninguém: é o valor em que o `videos` pousa um código que
não reconhece, para que a estratégia aditiva do contrato de mensagens não derrube uma
mensagem vinda de um `extracao` mais novo (ticket 009). Ele aparece na API porque mentir
sobre a causa seria pior que admitir desconhecê-la.

Um usuário que vê `FALHOU` sem nenhuma pista é uma demo ruim; o código resolve isso sem o
`videos` reivindicar o texto do usuário.

`detalheTecnico` do `ExtracaoFalhou` **nunca** aparece aqui: é log, não contrato.

## `POST /videos` — envio

Multipart com um campo, `arquivo` (`@RestForm FileUpload`).

Resposta **`202 Accepted`**, `Location: /videos/{id}`, corpo com a representação de Vídeo
em `RECEBIDO`.

`202` e não `201` porque, embora o recurso de fato já exista no `Location`, o que interessa
comunicar é que o trabalho **não terminou** — e `202` admite explicitamente um `Location`
como monitor de status. A escolha é reversível e não custa nada mudar.

### Rejeições na borda

Este contrato fixa **quais rejeições existem e qual a forma delas**; os *valores* foram
fixados pelo ticket 011 e estão na tabela.

| Situação | Status | Valor (ticket 011) |
|---|---|---|
| campo `arquivo` ausente ou vazio | `400` | — |
| content-type ou extensão fora da lista | `415` | extensões `mp4`, `avi`, `mov`, `mkv`, `webm`; content-type `video/*` |
| corpo acima do teto | `413` | `quarkus.http.limits.max-body-size=200M` |

A validação da borda é **declarativa, não probatória**: ela pergunta "você quis mesmo mandar
isso?". A prova de que o arquivo é um vídeo decodificável mora no `extracao`, porque medir
isso aqui exigiria ffmpeg na imagem do `videos`. Pelo mesmo motivo, o **teto de duração**
(20 min) não é cobrado nesta borda — ele vira `DURACAO_EXCEDIDA` depois do `202`, por e-mail.

## `GET /videos` — listagem

Escopo pelo `sub`, sem exceção.

| Parâmetro | Default | Nota |
|---|---|---|
| `estado` | — | filtro opcional, um dos quatro estados |
| `pagina` | `0` | |
| `tamanho` | `20` | |

Ordenação é **fixa** por `recebidoEm` decrescente — não há parâmetro de ordenação.

```json
{ "conteudo": [ /* … */ ], "pagina": 0, "tamanho": 20, "total": 57 }
```

Um usuário de demo tem cinco Vídeos, então a paginação não serve à demo — serve ao
requisito de "arquitetura que permita ser escalada", e custa pouco em Panache reativo.
Sem `Link` headers.

## `GET /videos/{id}` — consulta

`200` com a representação. `404` quando o Vídeo não existe **ou não é do usuário** — ver
"Vídeo de outro usuário".

## `GET /videos/{id}/pacote` — download

`200`, `Content-Type: application/zip`, `Content-Disposition: attachment`, corpo em
streaming pelo próprio `videos` (`RestMulti.fromUniResponse` + `toPublisher()`, ticket 005).
Nada de `toBytes()`.

**Stream, não redirect para presigned URL.** A pesquisa do ticket 005 recomendou e este
ticket decide, por três razões:

1. A AWS documenta presigned URL como *bearer token* — a posse do Vídeo é conferida uma
   vez, na emissão, e depois a URL vale, reutilizável, até expirar. O stream mantém a
   autorização contínua.
2. No Compose o host entra na assinatura, então `http://minio:9000` não funciona no `curl`
   do avaliador sem um presigner nomeado só para isso — uma peça a mais para explicar.
3. O MinIO não precisa ser exposto ao host.

Presigned URL fica registrada como alternativa conhecida na documentação de arquitetura,
não como caminho implementado.

### Quando o Pacote não existe

Dois casos, e eles não são o mesmo (ticket 019):

| Caso | Status | Sentido |
|---|---|---|
| Vídeo não está `CONCLUIDO` (`RECEBIDO`, `PROCESSANDO`, `FALHOU`) | `409 Conflict` | **ainda não** |
| Vídeo `CONCLUIDO`, mas o objeto expirou no MinIO | `410 Gone` | **não mais** |

`404` está errado nos dois: o Vídeo existe e é do usuário; o que falta é o Pacote.

A separação existe porque um cliente que recebe `409` racionalmente **repete** a requisição
— o trabalho pode terminar a qualquer momento — e um que recebe `410` sabe que insistir não
adianta. Reusar o `409` para os dois obrigaria a distinguir os sentidos pelo texto do
`detail`, que não é contrato. E o código de expiração **não** entra no enum `motivo`: aquele
enum é de falha de Extração, e expirar não é falhar — a Extração concluiu.

O prazo é a regra de ciclo de vida de 7 dias do bucket `pacotes` (ticket 011). Ele aparece na
descrição do OpenAPI e no `detail` do `410`, e **não** vira campo da representação de Vídeo:
quem apaga é o MinIO, então um instante calculado pela aplicação mentiria com precisão de
segundos no dia em que os dois divergissem. O `estado` também não ganha valor novo — ele
responde o que aconteceu com a Extração, e `CONCLUIDO` segue verdadeiro para sempre.

A descoberta é **preguiçosa**: ninguém varre o MinIO, e o `GET` que descobre a ausência
**não grava nada** no Postgres. A tabela `video` é o registro do que aconteceu, não um
espelho do bucket.

## Vídeo de outro usuário

**`404`**, o mesmo `404` de id inexistente. `403` confirmaria que aquele `id` existe; o
`404` não vaza nada, ao custo de mentir levemente — e o usuário não distingue os dois
casos, que é exatamente o ponto.

## Erros

`application/problem+json` (RFC 9457).

| Situação | status | `title` |
|---|---|---|
| Vídeo não é seu, ou não existe | `404` | `Video nao encontrado` |
| Pacote pedido e Vídeo não está `CONCLUIDO` | `409` | `Pacote indisponivel` |
| Pacote de Vídeo `CONCLUIDO` expirou no MinIO | `410` | `Pacote expirado` |
| Content-type ou extensão recusada | `415` | `Formato nao suportado` |
| Campo `arquivo` ausente ou vazio | `400` | `Requisicao invalida` |
| Corpo acima do teto | `413` | — gerado pelo Vert.x |
| Qualquer outra | `500` | `Erro interno` |

- `type` fixo em `about:blank`. O padrão permite, e inventar uma URI de tipo que não
  resolve é pior que não ter.
- `detail` em português, com o dado concreto.
- Sem `instance`.

Os campos do envelope são em inglês porque são do padrão; os **valores** seguem em
português. A incoerência de língua fica confinada ao envelope de erro.

**O `413` é a exceção que confirma a regra**: o Vert.x corta o corpo antes do JAX-RS, então
ele não passa por `ExceptionMapper` e **não sai como problem+json**. Isso é uma
inconsistência assumida, não um bug a caçar.

## OpenAPI e Swagger UI

O Swagger UI *é* a demo — não há outra interface.

### Autenticação na página

`@SecurityScheme` do tipo `oauth2`, fluxo `password`, apontando para o `token_endpoint` do
realm. O avaliador clica em **Authorize**, faz login com o usuário de demo e volta
autenticado, sem sair da página para buscar um token.

Sem essa declaração o `quarkus-smallrye-openapi` não descobre o Keycloak sozinho e não há
onde colar credencial nenhuma — a demo viraria `curl`.

A URL do realm **precisa ser configurável**, nunca literal na anotação: em `@QuarkusTest` o
Dev Services for Keycloak sorteia a porta. O Quarkus expõe propriedades
`quarkus.smallrye-openapi.*` que geram o security scheme a partir de configuração; qual
exatamente usar é detalhe de implementação, mas a regra "não literal" não é.

**Requisito para o realm** (soma-se ao claim `email` exigido pelo contrato de mensagens): o
client precisa aceitar *direct access grants*, senão o fluxo `password` não funciona.

### O que anotar

Só o `VideosResource`:

- `@Tag` no recurso;
- `@Operation(summary)` nas quatro operações;
- `@APIResponse` para cada status da tabela de erros — o gerador só declara o caminho feliz;
- `info` (título, versão, descrição) via `application.properties`, **não** por anotação.

Sem `@Schema` campo a campo: o gerador já acerta a partir dos tipos, e a anotação vira
ruído que envelhece. A exceção são os dois enums (`estado`, `motivo`), cujo significado o
tipo não carrega.
