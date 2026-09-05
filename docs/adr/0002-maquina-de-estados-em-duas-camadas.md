# A máquina de estados do Vídeo vive em duas camadas

O [ADR 0001](0001-politica-de-falhas.md) ancorou a unicidade da notificação num
`UPDATE ... WHERE id = ? AND estado = ?` dentro do `videos` — ou seja, escreveu uma regra de
negócio como cláusula SQL, enquanto o `AGENTS.md` do template exige que toda regra de negócio
viva no `core`, sem framework e sem banco. Decidimos **não escolher entre os dois**: o enum
`EstadoVideo`, no `core`, é o dono do grafo de transições e responde *"esta transição é
legal?"*; o `UPDATE` condicional, no adapter, responde *"fui **eu** quem de fato mudou a
linha?"*. São perguntas diferentes, e nenhuma das duas camadas consegue responder a outra: a
entidade não tem como garantir atomicidade, e o banco não tem como explicar o domínio.

Decidido no [ticket 009](../wayfinder/tickets/009-modelo-dominio-videos.md), emendado no
[ticket 027](../wayfinder/tickets/027-melhorias-medidas.md), que alargou o predecessor dos
estados terminais — ver *Predecessor é conjunto, não estado único*, abaixo —, e emendado de
novo no [ticket 031](../wayfinder/tickets/031-decisao-de-transicao-em-java.md), que descobriu
que **a primeira das duas perguntas nunca era feita em produção** — ver *A entidade entra no
caminho de produção*.

## Predecessor é conjunto, não estado único

`CONCLUIDO` e `FALHOU` aceitam **`RECEBIDO` ou `PROCESSANDO`** como predecessor; só
`PROCESSANDO` tem predecessor único. O grafo continua declarado num lugar só — o que mudou é
que `EstadoVideo.predecessores()` devolve um `Set`, e o `WHERE` do adapter virou `estado in ?`.

O motivo é medição, não estética. `ExtracaoIniciada` e `ExtracaoConcluida` viajam em **filas
independentes** e o contrato de mensagens não promete ordem entre elas. Quando a terminal
ganhava a corrida, o `UPDATE` exigia `PROCESSANDO`, não casava nada, alterava zero linhas e o
consumidor dava `ack` — o Vídeo ficava preso em `PROCESSANDO` **para sempre, com o `.zip` já
gravado no bucket**. O [ticket 025](../wayfinder/tickets/025-carga-conservacao.md) mediu
11/400 sob pico com uma réplica reiniciada e 34/39 depois de o `videos` cair; a prova não foi
raciocínio, foi o bucket: 45 de 45 presos tinham o Pacote lá. Isso é a requisição perdida que
o enunciado proíbe.

`PROCESSANDO` é informação de acompanhamento, não portão: o que ele significa para o usuário é
"alguém pegou", e pular esse aviso não invalida o desfecho. A alternativa de **reordenar no
consumidor** foi rejeitada por exigir estado e um timeout — inventa um problema para resolver
outro; e a de **uma fila só para os três eventos**, por quebrar o contrato do ticket 007 e
serializar o consumo.

A guarda de unicidade do e-mail do [ADR 0001](0001-politica-de-falhas.md) **não afrouxa**: o
`UPDATE` continua mudando a linha exatamente uma vez, saindo de `RECEBIDO` ou de
`PROCESSANDO`, e é a mudança que autoriza publicar. A `ExtracaoIniciada` que chega **depois**
da terminal não casa nada e recebe `ack`, que é o comportamento certo.

## A entidade entra no caminho de produção

Este ADR descreveu, desde o 009, um desenho de duas perguntas. Só a segunda rodava.

O levantamento do [ticket 031](../wayfinder/tickets/031-decisao-de-transicao-em-java.md), sobre
`develop @ 6128f33`: `EstadoVideo.predecessores()` tinha três chamadores em `src/main`, todos
no `VideoDataSourceAdapter`; `Video.marcaComoIniciada`, `marcaComoConcluida`, `marcaComoFalha`,
`EstadoVideo.transitaPara` e `EstadoVideo.terminal` tinham **zero**. O use case passava o id ao
gateway e o `UPDATE` fazia o resto — não havia instância de `Video` no caminho de escrita. O
estado só era atribuído em `novo`, que põe `RECEBIDO`, e em `reconstituir`, que copia a coluna.

A consequência não era estética: a suíte de use case inteira validava uma implementação que não
embarcava, e o que ela validava é a regra que decide se o Dono recebe e-mail.

O use case passa a carregar o Vídeo e a perguntar à entidade **antes** de tocar o banco. O
`false` significa reentrega fora de ordem e termina em `ack` sem `UPDATE`. O `UPDATE`
condicional não sai e continua sendo quem autoriza publicar: com o esquema fechado, a coluna
`estado` **é** a coluna de versão, e o `WHERE ... estado in ?` é um compare-and-swap. A guarda
de unicidade do [ADR 0001](0001-politica-de-falhas.md) não afrouxa. Um read-modify-write
clássico, com `@Version`, daria a mesma garantia por mais round-trips e uma coluna nova — e foi
recusado por isso, não pelo motivo que este ADR dava antes.

O preço, assumido: um `SELECT` a mais por evento nos três desfechos, e a posse do Vídeo
deixando de ser estrutural. O `VideoGateway` ganha `buscarPorId(UUID)` para o caminho de
mensageria, que não tem Dono a informar, e o `ArchitectureConstraintsTest` passa a proibir
`Resource` e controller HTTP de chamá-lo. A regra do [ticket 009](../wayfinder/tickets/009-modelo-dominio-videos.md)
continua valendo; ela deixa de ser impossível de furar e passa a ser verificada.

## Terminal para o outro terminal é corrida, não bug

Este ADR classificava `FALHOU → CONCLUIDO` como transição que o grafo não prevê de jeito
nenhum, e reservava `IllegalStateException` para ela. Isso nunca chegou a produção, porque a
entidade não era chamada — e é bom que não tenha chegado, porque **o caso é alcançável por
operação**:

a conexão com o broker cai enquanto o ffmpeg ainda roda; o broker reentrega; a terceira
entrega esgota o `x-delivery-limit`; `ExtracaoDlqConsumer` sintetiza `ExtracaoFalhou` e o Vídeo
vai a `FALHOU`; a réplica original, viva o tempo todo, termina a Extração e publica
`ExtracaoConcluida`. Chegam os dois terminais para o mesmo Vídeo, e nenhum dos dois lados
errou.

**Primeiro terminal vence.** É a única regra sã sob entrega ao-menos-uma-vez com um consumidor
de DLQ sintetizando desfecho, e é o que o `UPDATE` condicional já fazia sozinho: casa zero
linhas, `ack`, silêncio. A entidade passa a devolver `false` no mesmo caso, com log ou métrica
— a corrida é rara, e se ficar frequente queremos saber antes do Dono. Exceção aqui mandaria
uma queda de rede para a DLQ do `videos`.

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
  grafo continue declarado uma vez só. O grafo, aliás, sempre esteve declarado uma vez só:
  `transitaPara(d)` termina em `d.predecessores().contains(this)`, um `switch` para as duas
  portas. O que **está** declarado duas vezes é outra coisa — quais campos cada desfecho
  escreve, na lista dos `marcaComo*` e na lista do `SET` do `UPDATE`. Elas conferem hoje, e
  nada no build as compara; é o que `VideoDataSourceAdapterTest` passa a cobrir no ticket 031.
- **A assinatura do gateway é `CompletableFuture<Boolean>`.** `true` significa "a linha
  mudou", não "deu certo". É um retorno fácil de ignorar por acidente, e ignorá-lo é
  precisamente o bug do e-mail duplicado — as três transições devolvem boolean justamente
  para que o use case tenha de decidir o que fazer com ele.
- **Transição ilegal é retorno, não exceção — inclusive de um terminal para o outro.** Um
  evento fora de ordem é o caminho esperado, e o consumidor dá `ack` em todos os casos. Este
  ADR reservava `IllegalStateException` para `FALHOU` → `CONCLUIDO`; o ticket 031 mostrou que
  esse caso é corrida de rede, não bug — ver *Terminal para o outro terminal é corrida, não
  bug*. Não há transição do grafo que levante exceção.
- **O `WHERE` com `in` é exercitado contra Postgres, não só contra o dublê.** `estado in ?`
  com um `Set` de enum é HQL que nenhum teste do `core` alcança, e o BDD tampouco — ele monta
  `CONCLUIDO` atribuindo a entidade direto. `VideoDataSourceAdapterTest` existe para isso
  (ticket 027).
- **O `core` continua testável sem banco.** O grafo de transições tem teste unitário puro; o
  que exige `@QuarkusTest` é apenas a prova de concorrência — três entregas do mesmo
  `ExtracaoFalhou` produzindo **um** `VideoFalhou` (ticket 017).
