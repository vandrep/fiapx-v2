# Modelo de domínio e script de banco do serviço videos

- id: 009
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 007, 008

## Question

`videos` é o dono do estado. O enunciado exige entregar o "script de criação do banco de
dados" como documentação — então o esquema é entregável, não detalhe interno.

A decidir:

- Entidades e value objects do `core`: o que é **Vídeo**, o que é **Pacote**, e se a
  **Extração** é uma entidade persistida ou apenas a operação que o `extracao` executa.
- A máquina de estados como código: quem valida a transição (`RECEBIDO` → `PROCESSANDO` →
  `CONCLUIDO` | `FALHOU`) e o que acontece com uma transição inválida vinda de um evento
  fora de ordem.
- Esquema das tabelas, índices (a listagem filtra por dono e ordena por data), e o
  `init.sql` versionado — incluindo a criação dos databases por serviço.
- Migrations: Flyway ou `import.sql`/geração do Hibernate? O que é defensável na banca sem
  virar peso.
- Guarda de propriedade: onde exatamente mora a regra "só o dono vê o vídeo" — entidade,
  use case ou consulta do gateway. (Se ficar só na consulta, é fácil de furar depois.)
- Que gateways e presenters o `core` declara, seguindo o padrão do `AGENTS.md` do template.

**Restrição vinda do ticket 007**: `videos` precisa persistir o e-mail do dono (claim
`email` do token, capturado no upload) e o nome do arquivo original — sem os dois, o evento
`VideoFalhou` não pode ser montado e o `notificacao` fica sem o que mandar.
