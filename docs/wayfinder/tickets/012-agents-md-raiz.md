# AGENTS.md da raiz para o layout multi-módulo

- id: 012
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
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

## Resolução

O `AGENTS.md` da raiz existe, com 108 linhas contra as ~400 do template. A diferença de
tamanho é a decisão central deste ticket.

### O `AGENTS.md` não reescreve as regras de camada

O ticket perguntava o que copiar do template e o que referenciar, e ofereceu duas saídas
ruins: duplicar as regras (envelhece mal) ou apontar para o caminho do template (que vive
fora do repositório e é link morto para quem clona). A saída é uma terceira: apontar para o
**`ArchitectureConstraintsTest`**, que está dentro do repositório, é executável e é a
autoridade real — `./mvnw test` reprova quem violar, com arquivo e regra na mensagem.

Reescrever as regras num Markdown seria cache de um lookup barato: o arquivo está a um
`open` de distância e não mente. O `AGENTS.md` então carrega só o que o teste não consegue
dizer — o porquê, o layout multi-módulo, o que difere entre os três serviços, e os ponteiros.

Mesma lógica para a regra de camada de mensageria: ela já existe em
`docs/contratos/mensagens.md` § Camadas (consumidor `@Incoming` ≡ `Resource`, publicador ≡
`DataSourceAdapter`, `record` do contrato nunca cruza para o `core`). O `AGENTS.md` aponta,
não copia. Isso derrubou a última candidata a regra que só ele poderia carregar.

Os ponteiros são uma tabela **"quando você for X, leia Y"**, não uma lista de arquivos.
Ponteiro só ganha a linha se disser em que ramo ele dispara.

### Um arquivo na raiz, mais um `CLAUDE.md` de uma linha

Nada de `AGENTS.md` por serviço. As diferenças entre os três cabem numa tabela de três
colunas — banco, borda HTTP, o que roda fora do JVM — e ali ficam *comparáveis*, que é o que
importa quando a regra é "só `videos` tem banco". Abrir `extracao/AGENTS.md` se o ffmpeg
criar peso próprio.

O `CLAUDE.md` tem uma linha e nenhuma regra: só aponta para o `AGENTS.md`. É seguro barato
contra um modo de falha silencioso — se o suporte a `AGENTS.md` não estiver ativo no
ferramental, o arquivo seria inerte para o consumidor principal e *pareceria* estar
funcionando.

### A decisão do ffmpeg não virou ADR

Difícil de reverter ✅, trade-off real ✅ (JavaCV medido: 3,5× mais lento, 5× mais memória),
mas **não é surpreendente sem contexto** — `docs/pesquisa/ffmpeg-extracao.md` já carrega a
medição e o mapa já a indexa. O que faltava não era registro, era ponteiro. Virou uma linha
na seção de diferenças entre serviços, com o link para a pesquisa.

### As três cópias viraram um check, não uma frase

Aqui o ticket mudou de forma no meio da conversa. A pergunta original era onde documentar
"editar uma cópia significa editar as três". A resposta é que **prosa apodrece e check não**:
a regra virou build.

Duas trocas fizeram isso caber em duas linhas de código:

1. `MODULO_DO_SERVICO` deixou de ser `"videos"` fixo e passou a ser derivado do nome do
   diretório do módulo — `Path.of("").toAbsolutePath().getFileName()`. O CWD do surefire é o
   basedir, o mesmo pressuposto que `MAIN_SOURCES = Path.of("src/main/java")` já usava. Com
   isso as três cópias ficam **byte a byte idênticas**.
2. Sendo idênticas, a guarda é um `cmp` de três arquivos: `scripts/verifica-testes-arquiteturais.sh`.
   Sem exceção de linha, sem `grep -v`, sem nada que quebre quando o arquivo crescer.

Foi descartado **baixar o arquivo em tempo de build** para comparar. Torna o build
não-hermético: exige rede, quebra offline, e o que se valida deixa de ser o working tree e
passa a ser o que estava no servidor naquele instante — com uma janela em que os dois
divergem. Como o arquivo mora no mesmo repositório, é uma ida à rede para buscar o que já
está em disco.

Foi adiado o módulo Maven `test-support`, que é a eliminação de verdade da duplicação. Ele
custa um módulo, um pom e uma ordem de build para comprar o que o `cmp` compra por duas
linhas — e sacrifica a propriedade que faz o teste arquitetural valer: ele é **legível no
lugar**, aberto ao lado do código que julga. Continua sendo o escape hatch que o ticket 002
nomeou, para quando doer.

### A guarda mora no agregador, não nos módulos

Ela roda localmente, não só no CI, porque o ciclo de feedback tem que pegar a divergência no
`./mvnw test` e não no push. Mas um teste em `videos/` que lê `../extracao/src/test/...`
seria um módulo alcançando o vizinho — o acoplamento que o multi-módulo existe para impedir.

O lugar é o **parent agregador**, cuja jurisdição *é* o repositório: `exec-maven-plugin` com
`<inherited>false</inherited>`, preso à fase `validate` — a primeira do ciclo, então a falha
chega em segundos, antes de compilar qualquer coisa. Verificado: roda uma vez só (não quatro),
`./mvnw validate` passa com as cópias iguais e falha o build com as cópias divergentes.

Como `./mvnw verify` a partir da raiz já a executa, **não é preciso step de YAML separado**
no CI.

### Fatos de máquina ficaram de fora

"`./mvnw test` exige Docker de pé" entrou: é verdade do repositório, vale para qualquer
clone, e nenhum arquivo de config a confessa. "Não há JDK 21 nesta máquina, o build usa o
JDK 25 do jbang com `--release 21`" ficou de fora: é verdade de um notebook só, o repo é
público, e envelhece no dia em que o JDK 21 for instalado.

### Requisito duro entregue ao ticket 013

Se o workflow adotar matriz por módulo com `mvn -f <servico>/pom.xml`, o parent sai do
reator e a guarda das três cópias é **silenciosamente pulada**. Pelo menos um job precisa
rodar Maven a partir da raiz.

### Arquivos

- `AGENTS.md` (novo)
- `CLAUDE.md` (novo, uma linha)
- `scripts/verifica-testes-arquiteturais.sh` (novo)
- `pom.xml` — `exec-maven-plugin` em `validate`, `<inherited>false</inherited>`
- as três cópias de `ArchitectureConstraintsTest` — `MODULO_DO_SERVICO` derivado
