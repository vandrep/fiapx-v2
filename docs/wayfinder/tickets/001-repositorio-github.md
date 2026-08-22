# Repositório próprio e remote no GitHub

- id: 001
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Question

`fiapx-v2` hoje é um diretório **não versionado** dentro do repo
`/home/vandrep/projetos`. O enunciado exige "link do GitHub do(s) projeto(s)" e o CI/CD
depende de um repositório real.

Nada a decidir — trabalho manual que destrava a esteira: transformar `fiapx-v2` em repo
Git próprio (garantindo que ele saia do índice do repo pai), criar o repositório no
GitHub, publicar `main`, e confirmar que o GHCR está acessível para a conta.

Registre na resolução: URL do repositório, se é público ou privado, e qualquer segredo ou
permissão que o workflow vá precisar (`packages: write`, por exemplo).

## Resolução

**Repositório**: <https://github.com/vandrep/fiapx-v2> — **público**, dono `vandrep`
(conta pessoal, não organização), branch default `main`, já publicada
(`origin/main` = `b2a701d`).

**Saída do repo pai**: `/home/vandrep/projetos` é um repo Git, mas `fiapx-v2` **nunca
entrou no índice dele** — aparece apenas como untracked (`?? fiapx-v2/`). O requisito
está cumprido. Se o ruído no `git status` do pai incomodar, basta acrescentar
`fiapx-v2/` ao `.gitignore` do pai; não foi feito aqui por ser edição em outro repo.

**GHCR**: não foi possível *confirmar* por API — o token do `gh` desta máquina tem
escopos `gist, read:org, repo, workflow` e falta `read:packages`, então
`gh api user/packages` responde 403. Isso **não bloqueia o CI**: o push do workflow usa
o `GITHUB_TOKEN` da execução, não este token. O 403 só significa que um
`docker login ghcr.io` manual, local, exigiria antes:

```
gh auth refresh -s write:packages,read:packages
```

**O que o workflow vai precisar** (fatos apurados, entram no ticket de CI/CD):

- **Nenhum segredo novo.** O `GITHUB_TOKEN` da execução autentica no GHCR
  (`docker/login-action` com `username: ${{ github.actor }}`,
  `password: ${{ secrets.GITHUB_TOKEN }}`).
- **`permissions:` é obrigatório no YAML.** O default do repositório é
  `default_workflow_permissions: read` — sem um bloco explícito, o push falha com 403.
  O job de build precisa de:

  ```yaml
  permissions:
    contents: read
    packages: write
  ```

- **Actions está habilitado**, `allowed_actions: all` (actions de terceiros como
  `docker/build-push-action` podem ser usadas sem allowlist).
- **Namespace das imagens**: `ghcr.io/vandrep/fiapx-videos`,
  `ghcr.io/vandrep/fiapx-extracao`, `ghcr.io/vandrep/fiapx-notificacao` — o namespace do
  GHCR é o **owner**, em minúsculas, não o nome do repositório.
- **Pegadinha de visibilidade**: um package novo no GHCR nasce **privado**, mesmo em
  repositório público. Ou o package é tornado público na UI depois do primeiro push, ou
  quem for reproduzir a demo precisa de `docker login`. Como o Compose é o alvo de deploy
  e a banca pode querer subir o projeto, o primeiro push do CI deve ser seguido de
  "tornar público" nos três packages. Vale também vincular cada package ao repositório
  (`org.opencontainers.image.source` como label na imagem) para o link aparecer no GHCR.

**Higiene feita junto**: o repositório não tinha `.gitignore` — criado agora
(`target/`, `.quarkus/`, `*.class`, `__MACOSX/`, `.env`, preservando o
`maven-wrapper.jar`). Sem isso, o esqueleto Maven do ticket 002 despejaria `target/`
no índice no primeiro build.

**Sobrou versionado, de propósito ou não**: `.idea/` e
`docs/referencia/referencia/projeto-original/__MACOSX/` já estão rastreados — o
`.gitignore` não os remove retroativamente. Decisão deixada para o dono do repo.

## Correção (ticket 013)

A afirmação de que as imagens **nascem privadas** mesmo em repositório público está errada.
Verificado na primeira publicação real, com token anônimo no GHCR: `fiapx-videos`,
`fiapx-extracao` e `fiapx-notificacao` responderam `200` a `GET /v2/.../manifests/latest` sem
credencial nenhuma. O package criado pelo `GITHUB_TOKEN` de um repo público **herda a
visibilidade do repositório**. O passo manual que este ticket previa não é necessário.

O resto do ticket se sustenta: o `GITHUB_TOKEN` autentica no GHCR sem segredo novo, e o job
precisa mesmo declarar `packages: write`, porque o default do repo é `read`.
