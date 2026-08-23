# README.md do repositório

- id: 021
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 020

## Question

O repositório não tem `README.md`. Agora que o `docker-compose.yml` (ticket 020) sobe os cinco
serviços de ponta a ponta, o README passa a ser especificável: precisa cobrir o `docker compose
up` da demo (incluindo o `docker compose pull` das três imagens do GHCR antes do primeiro `up`),
o procedimento único de tornar os packages do GHCR públicos (ticket 013 — nascem privados por
padrão mesmo em repo público, e um `pull` anônimo falha até alguém marcar os três packages como
públicos no GitHub), como obter um token via Keycloak (`demo`/`demo`, `outro`/`outro`) para testar
pelo Swagger UI ou `curl`, e o mapa de leitura do repo (onde estão os contratos, ADRs,
`AGENTS.md`). Não é destino em si — é o documento que entrega a demo a quem nunca viu o projeto.
