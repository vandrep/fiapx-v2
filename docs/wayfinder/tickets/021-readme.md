# README.md do repositório

- id: 021
- label: wayfinder:task
- status: fechado
- assignee: vandrep (sessao de 2026-08-23)
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

## Resolução

[`README.md`](../../../README.md) na raiz, 148 linhas. Todo comando publicado nele foi
**executado contra o Compose de verdade** antes de entrar no arquivo — `pull`, `up -d`, token,
upload, polling do estado, download do `.zip` (3 frames reais) e o caminho de falha até o e-mail
no MailHog.

Estrutura: o que é o sistema (diagrama de fluxo + tabela dos três serviços), subir a demo,
tabela dos cinco consoles com credenciais, usar pelo Swagger UI e pelo `curl`, o caminho de
falha, os limites, desenvolver, e o mapa de leitura do repo.

Dois achados que só apareceram testando:

- **A premissa do próprio ticket estava errada**: não existe "procedimento único de tornar os
  packages do GHCR públicos". Os três já são anonimamente puxáveis — verificado por token
  anônimo do GHCR e `GET /v2/vandrep/fiapx-<servico>/manifests/latest`, `200` nos três. É
  exatamente o que o ticket 013 já tinha corrigido do ticket 001 (package criado pelo
  `GITHUB_TOKEN` em repo público herda a visibilidade dele); o corpo deste ticket ressuscitou a
  versão desmentida. A seção foi cortada em vez de escrita. De quebra, o índice do manifesto
  confirma `amd64` **e** `arm64`, o que vira uma linha do README para quem avalia em Apple Silicon.
- **`curl -F "arquivo=@video.mp4"` responde `415`.** O `curl` manda `application/octet-stream`
  por padrão, e a borda exige `video/*` (ticket 011). A forma correta é
  `-F "arquivo=@video.mp4;type=video/mp4"`, e o README diz isso em negrito no lugar onde dói —
  sem esse aviso, o primeiro comando que o avaliador copia falha com um erro que parece bug do
  sistema. É o argumento mais forte a favor de rodar tudo antes de publicar.

Decisões de conteúdo: o README **não repete** contrato, ADR nem regra de camada — aponta, na
mesma disciplina de ponteiro do `AGENTS.md` (ticket 012). Documenta o assíncrono como *fluxo*
(`202` → polling → `409` antes da hora), porque é o que mais confunde quem chega esperando
resposta síncrona. O `outro`/`outro` é citado com o motivo de existir (`404` no Vídeo alheio),
não como credencial solta. E o caminho de falha ganha seção própria com receita reproduzível
(`head -c 2000 /dev/urandom > quebrado.mp4`): o enunciado pede notificação de erro, e sem isso
ela não seria demonstrável.

Também corrigido de passagem: a resolução do ticket 020 apontava "ver ticket 021" para o script
de smoke, que é o ticket 022.
