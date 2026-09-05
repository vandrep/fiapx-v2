# Documentar a resposta de erro interno no OpenAPI

- id: 043
- label: wayfinder:bug
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...f6baade`, convertido em ticket com
aprovação do usuário. O contrato HTTP exige documentar os status da tabela de erros, mas
nenhuma das quatro operações declara a resposta `500`, apesar de existir um mapper de
erro interno.

## O que entregar

Quem consulta a API no Swagger deve encontrar a resposta de erro interno nas quatro
operações públicas, coerente com o contrato HTTP e o comportamento já implementado.

## Condições de aceite

- [x] Envio, listagem, consulta de Vídeo e download do Pacote declaram a resposta `500`.
- [x] A descrição está em português e corresponde ao erro interno previsto no contrato.
- [x] Conferir no documento OpenAPI gerado a presença da resposta nas quatro operações,
  sem modificar o comportamento HTTP ou os demais status existentes.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

As quatro operações de `VideosResource` passaram a declarar
`@APIResponse(responseCode = "500", description = "Erro interno: não foi possível concluir a
requisição")`. É a última linha da tabela de erros do contrato — "qualquer outra → `500` →
`Erro interno`" — que o `ProblemDetailMappers.ErroInterno` já cumpria em runtime e que só
faltava no documento. A descrição repete o `detail` que o mapper de fato emite, em vez de
parafraseá-lo: são as duas faces do mesmo erro, e divergirem no texto seria a mesma dívida de
tradução dupla que o contrato recusa em `motivo`.

Mudança puramente aditiva: nenhum status existente foi tocado, nenhum `ExceptionMapper` mudou,
e o comportamento HTTP é o de antes. O gerador só declara o caminho feliz
([`docs/contratos/http-videos.md`](../../contratos/http-videos.md) § O que anotar), então
status de erro só chega ao Swagger por anotação.

### Validação

`ErroInternoNoOpenApiTest` julga o **documento gerado**, e não as anotações do recurso: ele
faz `GET /q/openapi` num `@QuarkusTest` e procura `responses.'500'` em
`post /videos`, `get /videos`, `get /videos/{id}` e `get /videos/{id}/pacote`, um caso
parametrizado por operação. É a condição de aceite escrita como teste — quem lê a API lê o
Swagger, não o mapper.

Rodado vermelho antes da mudança: as quatro reprovaram, cada uma nomeando a operação que
faltava. Verde depois. Suíte completa a partir da raiz, verde nos três serviços (118 + 268 +
24 testes).

### Achados da revisão de dois eixos

**Standards**: nenhuma violação. A repetição da mesma string nas quatro anotações foi
levantada como possível Código Duplicado e suprimida pela convenção local — o arquivo já lista
cada status por operação, e uma constante esconderia a tabela que o contrato quer visível.

**Spec**: nenhum requisito faltando nem escopo a mais. O único ponto — a descrição parafraseava
o `detail` do mapper em vez de repeti-lo — foi acolhido, e é o texto que está no código.

### Falha ambiental, separada do resultado

A porta 8081 do `@QuarkusTest` colide com o Keycloak do Compose de pé, daí
`-Dquarkus.http.test-port=0` em toda execução, como no ticket 041. Uma execução da suíte
também morreu em `bind: address already in use` ao subir o Ryuk do Testcontainers; a
reexecução passou sem nenhuma mudança de código. Nada disso é do código.
