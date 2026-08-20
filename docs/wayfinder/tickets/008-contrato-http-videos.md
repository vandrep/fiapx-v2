# Contrato HTTP do serviço videos

- id: 008
- label: wayfinder:grilling
- status: aberto
- assignee:
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
