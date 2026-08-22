# Pipeline de CI/CD: verify e push das três imagens para o GHCR

- id: 013
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 002

## Question

Os fatos já estão fechados: o `GITHUB_TOKEN` autentica no GHCR sem segredo novo, o job
precisa declarar `packages: write`, as imagens vão para `ghcr.io/vandrep/fiapx-<servico>`
e nascem privadas (ticket 001). O esqueleto compila e as três imagens constroem a partir
do `target/quarkus-app` já empacotado, com uma única compilação Maven (ticket 002).

Falta decidir a forma do workflow:

- Um job só rodando `./mvnw verify` na raiz, ou um job por módulo em matriz? A matriz dá
  feedback granular e paralelismo, mas recompila as dependências e triplica o custo de
  levantar Dev Services.
- Cache do Maven: `setup-java` com `cache: maven` basta, ou é preciso `actions/cache` com
  chave própria?
- `verify` exige Docker no runner (Dev Services do Postgres em `videos`) — o runner
  `ubuntu-latest` tem, mas quanto isso custa em tempo?
- O push das imagens roda em todo commit de `main`, só em tag, ou ambos com tags
  diferentes? O mapa fixou "tag do commit"; falta decidir se há também `latest`.
- Quem torna os packages públicos, e isso é passo manual único ou entra no workflow?
- O build das imagens depende do `verify` ter passado, ou são jobs independentes?

Requisito duro vindo do ticket 012: a guarda que cobra as três cópias idênticas de
`ArchitectureConstraintsTest` está presa à fase `validate` do **parent agregador**. Se o
workflow adotar matriz por módulo com `mvn -f <servico>/pom.xml`, o parent sai do reator e a
guarda é **silenciosamente pulada** — pelo menos um job precisa rodar Maven a partir da raiz.
Em compensação, `./mvnw verify` na raiz já a executa: não é preciso step de YAML separado.

O produto é o `.github/workflows/*.yml` funcionando, verificado por um push real.
