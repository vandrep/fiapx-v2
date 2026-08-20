# Repositório próprio e remote no GitHub

- id: 001
- label: wayfinder:task
- status: aberto
- assignee:
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
