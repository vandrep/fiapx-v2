# AGENTS.md da raiz para o layout multi-módulo

- id: 012
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 002

## Question

O esqueleto está de pé (ticket 002), mas o repositório não tem `AGENTS.md`. O do template
descreve um monólito modular single-module e **está errado em pontos que importam** para
quem for escrever código aqui:

- o pacote base é `br.com.fiapx` e cada serviço tem **exatamente um** módulo de negócio,
  homônimo — a frase "adicione novos módulos de negócio ao lado dele" agora é proibida
  pelo próprio teste arquitetural;
- `ArchitectureConstraintsTest` existe em três cópias, uma por módulo, e duas de suas
  guardas foram relaxadas — quem editar uma cópia precisa saber que deve editar as três;
- só `videos` tem banco; `@WithTransaction` e `DataSourceAdapter` são proibidos nos
  outros dois;
- `./mvnw test` roda na raiz e exige Docker.

O que decidir:

- Um `AGENTS.md` só na raiz, ou um na raiz mais um por serviço quando o serviço tiver
  regra própria (ffmpeg em `extracao`, sem-HTTP em `notificacao`)?
- O que copiar do template e o que referenciar? Duplicar as regras de camada envelhece
  mal; referenciar um caminho fora do repositório quebra para quem clona.
- Onde documentar a decisão do ffmpeg como processo externo — aqui ou num ADR?

Consulte `writing-for-agents`. O produto é o arquivo escrito, não um plano dele.
