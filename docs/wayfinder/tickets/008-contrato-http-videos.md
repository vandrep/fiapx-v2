# Contrato HTTP do serviço videos

- id: 008
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep (sessao de 2026-08-21)
- bloqueado-por: 004, 005

## Question

`videos` é a única borda pública do sistema. Sem UI, este contrato **é** o produto: é o
que a banca vê no Swagger UI e o que o script de smoke exercita.

A decidir:

- Endpoints: envio do vídeo, listagem de status do usuário, consulta de um vídeo,
  download do Pacote. Verbos, paths, códigos de resposta.
- O envio é síncrono ou retorna `202 Accepted` com a localização do recurso? (A resposta
  parece óbvia dado o processamento assíncrono, mas o formato do corpo e do `Location`
  não é.)
- Formato do upload: multipart com que nome de campo, que validações na borda (extensão,
  tamanho, content-type) e que erro para cada rejeição.
- Listagem: filtros, ordenação, paginação — e a garantia de que o `sub` do token, não um
  parâmetro, determina o escopo.
- Download do Pacote: redirect para presigned URL do MinIO ou stream pelo serviço? (Depende
  do achado do ticket 005 e tem implicação direta de autorização.)
- Erros: formato do corpo de erro, e quais são os exception mappers necessários.
- O que o OpenAPI precisa declarar para o Swagger UI virar demo apresentável.

## Resolução

Contrato completo em [`docs/contratos/http-videos.md`](../../contratos/http-videos.md).
Doze decisões, todas em conversa; nenhuma pesquisa nova foi necessária — os tickets 004 e
005 já tinham posto os fatos na mesa.

- **Quatro endpoints em português**, coerentes com o glossário: `POST /videos`,
  `GET /videos`, `GET /videos/{id}`, `GET /videos/{id}/pacote`. O Pacote é sub-recurso
  porque não tem identidade própria. Sem `DELETE`.
- **`202 Accepted`** no envio, com `Location` e a representação de Vídeo no corpo. `201`
  era defensável (o recurso já existe no `Location`), mas o que interessa comunicar é que
  o trabalho não terminou.
- **Uma representação só** para `POST`, `GET` individual e item de listagem — sete campos,
  nenhuma chave de MinIO entre eles.
- **`motivo` expõe o código, nunca a frase.** Este era o ponto de atrito real com o
  contrato de mensagens, que dá o texto humano ao `notificacao`: uma frase aqui criaria uma
  segunda tradução do mesmo enum. O código dá a informação sem duplicar a tradução — e faz
  o `codigoMotivo` virar contrato público, o que foi registrado no `CONTEXT.md`.
- **Download por stream, não presigned URL.** Presigned é *bearer token* (autorização
  conferida uma vez, na emissão) e o host entra na assinatura, quebrando o `curl` do
  avaliador no Compose. Fica como alternativa documentada, não implementada. Pacote
  indisponível é `409`, não `404` — o Vídeo existe.
- **`404` para Vídeo de outro usuário**, idêntico ao de id inexistente: `403` confirmaria
  a existência do id.
- **`application/problem+json`** com seis situações mapeadas, `type` em `about:blank`. O
  `413` é cortado pelo Vert.x antes do JAX-RS e **não** sai como problem+json — assumido.
- **Swagger UI como demo**: `@SecurityScheme` oauth2 com fluxo `password` para o botão
  Authorize funcionar, URL do realm configurável (o Dev Services sorteia porta). Anotação
  só no `VideosResource`; `info` por `application.properties`.

Divisão de fronteira com o ticket 011, que se sobrepunha: **008 fixa a forma das rejeições
(`400`/`413`/`415`), 011 fixa os números** — teto de tamanho, lista de extensões, teto de
duração.

Requisito novo para o realm do Keycloak, somado ao claim `email` do ticket 007: o client
precisa aceitar *direct access grants*, senão o fluxo `password` do Authorize não funciona.
