# Pipeline de CI/CD: verify e push das três imagens para o GHCR

- id: 013
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
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

## Resolução

O produto é [`.github/workflows/ci.yml`](../../../.github/workflows/ci.yml): **um arquivo,
um job, ~90 linhas** — mais da metade delas comentário, porque quase toda escolha aqui é
contraintuitiva e o YAML não tem onde se defender sozinho.

### Um job na raiz, não matriz por módulo

`./mvnw verify` a partir da raiz, num job único chamado `build`. A matriz por módulo dá
feedback granular e paralelismo real — o repo é público, então o runner tem 4 vCPU e os
minutos são **gratuitos e ilimitados**, e três jobs somam 12 vCPU. O argumento de custo que
normalmente derruba a matriz **não se aplica** aqui, e é honesto dizer isso.

O que a derruba é outra coisa. Primeiro, o requisito duro do ticket 012: a guarda que cobra
as três cópias idênticas de `ArchitectureConstraintsTest` está presa à fase `validate` do
**agregador**, então `mvn -f <servico>/pom.xml` a pula em silêncio — a matriz precisaria de
um quarto job na raiz só para isso, o que a torna estritamente mais cara que a alternativa.
Segundo, e decisivo: **a velocidade que ela compra ninguém está gastando.** Medição local do
`verify` na raiz: **1m14s** local (`user 8m31s` — o build já usa ~7 núcleos). Medido depois
no runner de 4 vCPU, com pull frio do `postgres:17`: **1m30s**, contra os 4 a 7 minutos que
eu havia estimado. Num projeto de uma pessoa isso não é gargalo nem de longe. Se passar de
~10 minutos, a matriz se justifica; hoje não.

Cache do Maven por `setup-java` com `cache: maven` — uma linha, e resolve o caso que dói
(baixar o BOM do Quarkus a cada run).

### Um job também para as imagens: `verify` e build no mesmo runner

Não há job separado com `upload/download-artifact`. Os três `Dockerfile` são single-stage
sobre `target/quarkus-app`, e o `verify` acabou de gerar os três no runner: separar
significaria transferir centenas de MB para comprar uma fronteira que não serve a nada.
Como consequência, imagem só é construída depois dos testes passarem — publicar imagem que
não passou no teste é inaceitável numa entrega de banca.

### Multi-arch: a decisão de maior valor do ticket

`linux/amd64,linux/arm64` na `main`; só `amd64` em PR.

A avaliação será **na máquina do avaliador**, e essa máquina pode ser Apple Silicon. Imagem
só-`amd64` roda sob emulação QEMU: tolerável para `videos` e `notificacao`, potencialmente
**fatal para `extracao`**, que invoca `ffmpeg` como processo externo (ticket 006) — ffmpeg
emulado pode estourar o tempo da demo ao vivo.

O custo que normalmente torna multi-arch proibitivo — compilar tudo duas vezes sob emulação
— **não existe aqui**: os Dockerfiles só copiam um `quarkus-app` pronto, que é Java puro e
agnóstico de arquitetura. O único passo dependente de arquitetura no repositório inteiro é o
`apk add --no-cache ffmpeg` do `extracao`, que é download de pacote, não compilação. Em PR a
segunda plataforma é dispensada: o que quebra num Dockerfile quebra igual nas duas.

Isso exige buildx, o que fecha a escolha da ferramenta: `docker/build-push-action` com
`setup-qemu-action` e `setup-buildx-action` — `docker build` cru não faz multi-arch.
**Sem `metadata-action`** (são duas tags fixas, mais legíveis escritas à mão que geradas) e
**sem cache de layer do GHA** (a camada que importa é o `quarkus-app`, que muda a cada
commit — cachear o que sempre invalida é overhead). `provenance: false` mantém a listagem do
package limpa, sem as entradas `unknown/unknown` do attestation OCI.

### Gatilhos, tags e concorrência

`push` na `main` e `pull_request`; publica só na `main`. Em PR as imagens são **construídas
sem push**, senão um `Dockerfile` quebrado passa verde e só explode depois do merge.

Duas tags: `${{ github.sha }}` e **`latest`**. O `latest` não é hábito preguiçoso — é o que
torna o Compose demonstrável, porque ninguém quer editar três SHAs antes de gravar o vídeo.

`concurrency` **assimétrico**: `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`.
PR cancela livre; `main` nunca, porque um run cancelado no meio deixa as imagens sem publicar
e o `latest` apontando para um commit anterior, sem aviso nenhum. É o modo de falha silenciosa
que estragaria a demo.

`timeout-minutes: 30` contra o default de 360: o modo de falha realista é Testcontainers
esperando um container que nunca fica pronto, e sem teto isso queima seis horas antes de
falhar. `permissions` no nível do job — `contents: read` e `packages: write`, nada mais.

### Nenhum `*IT.java`, e a linha do `AGENTS.md` que estava errada

`skipITs` é `true` no parent e não existe um único `*IT.java` no repositório: **`verify` e
`test` são hoje a mesma coisa** mais o empacotamento. O `AGENTS.md` mandava usar `verify`
"quando a mudança tocar integração" — conselho que não fazia nada.

A regra passa a ser explícita: **não escrever `*IT.java`**. Teste integrado aqui é
`@QuarkusTest` no surefire, que já sobe Dev Services de verdade; o failsafe só ganharia
sentido para testar o artefato empacotado (`@QuarkusIntegrationTest`, imagem nativa), fora de
escopo. Passar `-DskipITs=false` só no CI seria pior que tudo: cria a classe de bug "passa na
minha máquina, quebra no CI" pelo avesso.

### Packages públicos: passo manual, uma vez, e obrigatório

As imagens nascem **privadas** mesmo em repo público (ticket 001), e a avaliação é na máquina
do avaliador — então torná-las públicas não é conveniência, é requisito de entrega. Num clone
limpo `docker compose build` **falha**: `target/` está no `.gitignore` e os Dockerfiles não
compilam nada. O avaliador ou instala JDK 21 + Maven, ou puxa imagem pronta. Puxar é o único
caminho digno de uma demo.

Continua manual: automatizar exigiria um PAT com `write:packages` — isto é, exatamente o
segredo novo que o ticket 001 comemorou não precisar. O `GITHUB_TOKEN` não muda visibilidade
de package. **Procedimento, uma vez, após o primeiro run verde**, para cada um de
`fiapx-videos`, `fiapx-extracao`, `fiapx-notificacao`:

> github.com/vandrep?tab=packages → o package → *Package settings* → *Danger Zone* →
> *Change visibility* → **Public**.

### Proteção da `main`: ruleset idêntico ao dos repos anteriores

Não haverá grupo, mas o fluxo será o de grupo. Adotado o mesmo repository ruleset dos
trabalhos anteriores do curso, com um ajuste: `context` passa de `service-ci-validate` para
**`build`**. Regras: `deletion`, `non_fast_forward`, `pull_request` com
`required_approving_review_count: 0` e `allowed_merge_methods: ["merge"]`,
`required_status_checks` com `strict: false`.

Duas armadilhas desarmadas antes de aplicar:

1. **Zero aprovações é obrigatório, não frouxidão.** Com um único colaborador, exigir uma
   aprovação trava o repositório para sempre: o GitHub não deixa ninguém aprovar o próprio PR.
2. **`require_extra_approval_for_unattributed_changes: true` combinado com zero aprovações
   travaria qualquer commit não atribuído.** Verificado pela API antes de aplicar: o commit
   `759bb4d` já publicado aparece como `author: vandrep`, `committer: vandrep` — os commits
   estão atribuídos e a regra não dispara. Risco residual não verificável daqui: o trailer
   `Co-Authored-By` não corresponde a uma conta do GitHub; se um PR travar por isso, remover
   a regra ou o trailer.

`bypass_actors: []` fica como no original. A válvula de escape existe fora da regra — como
dono, alternar `enforcement` para `disabled` destrava o repo se o CI cair na véspera da entrega.

O `id` de repositório do ruleset importado é **o nome do job**: renomear `build` no YAML
derruba o status check exigido e trava todo PR esperando algo que nunca roda. Daí o job se
chamar `build`, em inglês e sem acento, contrariando de propósito a convenção de português do
resto do repo — este identificador é configuração remota, não prosa.

### O que isto fixa para outros tickets

- **Compose** (ainda não especificado): referencia `ghcr.io/vandrep/fiapx-<servico>:latest`,
  **sem chave `build:`**. O avaliador puxa, não constrói.
- **`README.md`**: ainda não existe e não estava ticketado; o procedimento de tornar os
  packages públicos e o `docker compose up` da demo pertencem a ele.

### Tickets como arquivos, não como Issues

Levantado durante a discussão, e decidido **manter markdown** — `docs/wayfinder/TRACKER.md`
segue valendo. O argumento a favor de Issues era o mesmo do fluxo de grupo, mas o que o fluxo
de grupo exige de fato é revisão antes de entrar na `main`, e isso o PR entrega. Os tickets
sendo versionados junto do código é vantagem que Issue nenhuma tem: a resolução do 013 e o
`ci.yml` entram no mesmo commit, e quem revisa o PR lê a decisão ao lado da mudança. Migrar 19
tickets com referências cruzadas sairia direto das 5,5 semanas.

### Verificação por push real

Run [32571128525](https://github.com/vandrep/fiapx-v2/actions/runs/32571128525): **verde em
2m40s** no total. `verify` 1m30s; os três builds multi-arch somaram ~50s, e o log confirma
camadas `linux/amd64` **e** `linux/arm64` resolvidas nas três imagens. O multi-arch saiu ainda
mais barato do que o argumento previa — o que era esperado, já que ele não compila nada.

Uma anotação não-fatal no run: as actions `docker/*` ainda declaram Node.js 20, que o runner
força para Node 24. É aviso do ecossistema, não do nosso YAML; some quando a Docker publicar
as versões novas.
