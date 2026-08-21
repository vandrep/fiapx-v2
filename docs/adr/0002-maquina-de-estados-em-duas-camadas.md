# A máquina de estados do Vídeo vive em duas camadas

O [ADR 0001](0001-politica-de-falhas.md) ancorou a unicidade da notificação num
`UPDATE ... WHERE id = ? AND estado = ?` dentro do `videos` — ou seja, escreveu uma regra de
negócio como cláusula SQL, enquanto o `AGENTS.md` do template exige que toda regra de negócio
viva no `core`, sem framework e sem banco. Decidimos **não escolher entre os dois**: o enum
`EstadoVideo`, no `core`, é o dono do grafo de transições e responde *"esta transição é
legal?"*; o `UPDATE` condicional, no adapter, responde *"fui **eu** quem de fato mudou a
linha?"*. São perguntas diferentes, e nenhuma das duas camadas consegue responder a outra: a
entidade não tem como garantir atomicidade, e o banco não tem como explicar o domínio.

Decidido no [ticket 009](../wayfinder/tickets/009-modelo-dominio-videos.md).

## Considered Options

**Só o `UPDATE` condicional** foi rejeitado. É atômico, é a coisa mais curta de escrever e
sozinho já cumpriria o ADR 0001. Mas o grafo de estados passaria a existir apenas espalhado
por cláusulas `WHERE` de três adapters: sem teste unitário, sem um lugar onde ler quais
transições existem, e sem o `ArchitectureConstraintsTest` protegendo coisa alguma — ele
verifica que o `core` não conhece banco, não que o banco não virou o `core`.

**Só a entidade** foi rejeitado, e é a opção perigosa, porque *parece* a mais limpa. Carregar
o Vídeo, perguntar à entidade se a transição é legal e gravar é um read-modify-write sem
guarda: dois consumidores concorrentes do mesmo `ExtracaoFalhou` reentregue leem
`PROCESSANDO`, ambos concluem que a transição é legal, e ambos publicam `VideoFalhou`. É
exatamente o e-mail duplicado que o ADR 0001 existe para impedir — e o contrato de mensagens
garante que a reentrega vai acontecer, porque `x-delivery-limit=3` conta *entregas*.

**Um VO genérico de transição** (`aplicarTransicao(Transicao)`) foi rejeitado por carregar
payload heterogêneo: `marcarConcluido` precisa de chave do Pacote, quantidade de frames e
tamanho; `marcarFalhou` precisa do motivo; nenhuma transição usa os dois conjuntos. O saco
comum esconderia, em tempo de compilação, qual campo pertence a qual transição.

## Consequences

- **Parece duplicação e não é.** Quem ler o código vai encontrar o predecessor exigido
  declarado em `EstadoVideo` *e* repetido no `WHERE`, e a tentação de "consertar" removendo
  um dos dois é real. O `WHERE` não repete a regra: ele a *executa* sob concorrência. O
  predecessor não é literal no adapter — vem do `core`, passado pelo use case, para que o
  grafo continue declarado uma vez só.
- **A assinatura do gateway é `CompletableFuture<Boolean>`.** `true` significa "a linha
  mudou", não "deu certo". É um retorno fácil de ignorar por acidente, e ignorá-lo é
  precisamente o bug do e-mail duplicado — as três transições devolvem boolean justamente
  para que o use case tenha de decidir o que fazer com ele.
- **Transição ilegal é retorno, não exceção.** Um evento fora de ordem é o caminho esperado,
  e o consumidor dá `ack` nos dois casos. `IllegalStateException` fica reservada para
  transição que o grafo não prevê de jeito nenhum (`FALHOU` → `CONCLUIDO`), que aí é bug.
- **O `core` continua testável sem banco.** O grafo de transições tem teste unitário puro; o
  que exige `@QuarkusTest` é apenas a prova de concorrência — três entregas do mesmo
  `ExtracaoFalhou` produzindo **um** `VideoFalhou` (ticket 017).
