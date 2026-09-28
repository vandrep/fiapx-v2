# O `Rastro` do `videos` credita os saltos de thread a dois mecanismos que ele não usa

- id: 090
- label: ready-for-agent
- status: fechado
- assignee: agente
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `f7257ed..cd18acc`, convertido em ticket com aprovação do
usuário. É o que sobrou do [088](088-rastro-do-notificacao-descreve-recursos-alheios.md) depois
que a `## Correção` dele estreitou o alvo: o 088 afirmou que a menção ao `@Blocking` estava
obsoleta nas cópias do `videos` **e** do `extracao`, e estava errado quanto ao `extracao`.

## O problema

O `Rastro` dos três serviços diz, na § *Por que um span nosso no consumo*, quem carrega o span
de `naMensagem` pelos saltos de thread da cadeia:

> Quem o carrega pelos saltos de thread da cadeia (worker pool do `@Blocking`, thread do SDK da
> AWS) e o contexto duplicado do Vert.x

No `extracao` a frase está **certa**, e o 088 já verificou: o `ExtrairVideoConsumer` anota
`@Blocking` (linha 86) e a cadeia de Extração passa pelo SDK da AWS nas duas idas ao MinIO. No
`notificacao` ela já foi corrigida pelo 088. Sobra o `videos`, onde os dois mecanismos são
suspeitos por motivos diferentes:

- **`@Blocking` não existe em código de produção do `videos`.** `grep -rn "@Blocking"
  videos/src/main` devolve uma linha só, e é este próprio javadoc. Os três consumidores do
  `ExtracaoEventosConsumer` devolvem `Uni` com ack manual, sem a anotação.
- **A thread do SDK da AWS é mais sutil, e talvez esteja invertida.** O `videos` fala com o MinIO,
  mas pelo que se vê o gateway de arquivo só é alcançado por `BaixarPacoteUseCase` e
  `PublicarExtrairVideo` — nenhum dos três `@Incoming` desemboca ali, então a cadeia de
  `naMensagem` do `videos` pode nunca tocar o SDK. Pior: quando o SDK aparece, no caminho da
  borda HTTP, o `ArquivoMinioAdapter.noContextoDeChamada` existe justamente para **sair** da
  thread dele e devolver a continuação ao contexto Vert.x de quem chamou. Se for isso, a thread
  do SDK não é algo que o contexto duplicado atravessa aqui — é algo de que este serviço
  ativamente se afasta, e citá-la como exemplo do mecanismo diz quase o oposto do que acontece.

O defeito é o mesmo do 088, e a razão de importar é a mesma: nenhum span muda, mas o
[061](061-travamento-raro-com-o-sdk-desligado.md) já descartou uma hipótese inteira por acreditar
numa frase desatualizada deste mesmo javadoc.

## O que entregar

A frase corrigida para os saltos de thread que a cadeia de `naMensagem` do `videos` de fato dá —
**depois de determinar quais são**, que é o trabalho real deste ticket e a razão de ele não ser
uma troca de palavras. A pergunta a responder é: entre o `@Incoming` do `ExtracaoEventosConsumer`
e o fim da cadeia, o trabalho troca de thread em algum ponto? Se troca, onde; se não troca, a
frase precisa dizer isso em vez de listar mecanismos.

Duas balizas para quem executar:

1. **Não force a convergência com as outras duas cópias.** O `AGENTS.md` § *As cópias deliberadas
   entre serviços* autoriza o `Rastro` a divergir exatamente aqui, e o 088 já usou essa
   autorização nas três.
2. **O mecanismo geral fica.** O que a frase explica — que é o contexto duplicado do Vert.x, e
   não a thread, que guarda o contexto do OpenTelemetry — é verdadeiro nos três serviços e é o
   que a § seguinte (*Onde o escopo pode atravessar thread*) depende. O que está em questão são
   os exemplos entre parênteses, não a regra.

Vale conferir, de passagem, um achado vizinho da mesma família: o javadoc de
`ArquivoMinioAdapter.noContextoDeChamada` diz que "o retry, quando dispara, retoma na thread do
scheduler do fault tolerance". O `@Retry` saiu no [061](061-travamento-raro-com-o-sdk-desligado.md)
e a sexta regra do teste arquitetural proíbe o interceptor desde então — não há mais scheduler de
fault tolerance em lugar nenhum. A frase descreve um mecanismo que não existe mais, e ela está no
mesmo caminho de código que este ticket precisa mapear.

## Critérios de aceite

- [x] A frase dos saltos de thread no `Rastro` do `videos` descreve o que a cadeia de
      `naMensagem` **deste** serviço faz, verificado e não suposto
- [x] Nenhuma menção a `@Blocking` sobrou no `Rastro` do `videos` sem que o `videos` use a
      anotação
- [x] O papel do contexto duplicado do Vert.x continua dito, e a § *Onde o escopo pode atravessar
      thread* continua fazendo sentido depois da edição
- [x] As cópias do `extracao` e do `notificacao` não foram tocadas para convergir
- [x] Nenhum span, nome de span ou atributo mudou
- [x] `./mvnw test` verde a partir da raiz

## Resolução

**A cadeia de `naMensagem` do `videos` não troca de thread.** Medido, não deduzido: o consumo
inteiro — entrada do `@Incoming`, `UPDATE` de transição, publish do `VideoFalhou`, `UPDATE` da
marca de publicação, fim da cadeia e ack — roda na **mesma event loop** que entregou a mensagem.

A medição foi por sonda temporária (`Thread.currentThread().getName()` na entrada do
`ExtracaoEventosConsumer`, dentro do `Rastro`, em volta de cada `RepeticaoNoPostgres.executar`,
em volta do `emitter.send` e no ack) rodando o `ExtracaoRapidaPelaBordaTest`, que é o mais longo
dos três consumos e o único que passa pelos três recursos. As dez linhas de sonda do consumo
saíram todas em `vert.x-eventloop-thread-7`, e a entrada registrou `duplicado=true`. As sondas
foram removidas depois; o que ficou no repositório é o javadoc.

Os dois mecanismos citados caíram pelo motivo que o ticket suspeitava:

- **`@Blocking`**: nenhum dos três consumidores tem a anotação — devolvem `Uni` com ack manual.
- **SDK da AWS**: o `ArquivoGateway` só é alcançado por `BaixarPacoteUseCase` e por
  `PublicarExtrairVideo` (borda HTTP e reconciliação), e nenhum `@Incoming` desemboca neles. A
  inversão que o ticket levantou está dita no javadoc: quando o SDK aparece, o
  `noContextoDeChamada` existe para **sair** da thread dele.

**O achado que a medição acrescentou, e que salva a § seguinte.** Sem salto de thread, a
pergunta vira "por que o contexto duplicado ainda importa aqui?". Importa porque a cadeia **se
interrompe** sem mudar de thread: a repetição do `RepeticaoNoPostgres` espera 2 s antes de
reassinar, e no intervalo não há quadro de pilha onde o contexto pudesse estar preso. Medido
num `@QuarkusTest` temporário que rodou `executar` num contexto duplicado com uma falha
transitória injetada: a continuação volta no **mesmo** contexto duplicado, com
`isOnDuplicatedContext()` verdadeiro, e o span segue corrente do outro lado da espera. É por
isso que o mecanismo geral fica de pé — é o contexto que guarda o span, e a thread é só onde ele
calhou de rodar.

O achado vizinho também foi corrigido: o javadoc de `ArquivoMinioAdapter.noContextoDeChamada`
não fala mais em "thread do scheduler do fault tolerance" — esse scheduler saiu com o `@Retry`
no [061](061-travamento-raro-com-o-sdk-desligado.md). No lugar ficou o que é conferível pela
leitura: a repetição do `ArquivoMinioClient` fica **dentro** da operação que chega à ponte, então
o `emitOn` a alcança seja qual for a thread em que ela retome.

As cópias do `extracao` e do `notificacao` não foram tocadas. Nenhum span, nome de span ou
atributo mudou.

### Verificação

`./mvnw test` verde a partir da raiz: 141 `videos`, 280 `extracao`, 29 `notificacao`.

## Correção (revisão do 090)

O `/code-review` da entrega achou o mesmo defeito nos dois eixos, e ele é **na frase acima e no
javadoc**, não na conclusão. O parágrafo errado fica onde está, como manda o `TRACKER.md` § *O
que pode mudar num ticket `fechado`*; o que ele diz de errado está aqui.

**A enumeração dos chamadores do `ArquivoGateway` estava errada, e nos dois sentidos.** A
`## Resolução` e o javadoc diziam que o gateway "só é alcançado por `BaixarPacoteUseCase` e por
`PublicarExtrairVideo`". Falta um chamador e sobra outro:

- **Falta o `EnviarVideoUseCase`**, que chama `gravarVideo` (linha 43) — é o caller real do SDK,
  e o javadoc do próprio `ArquivoMinioAdapter.noContextoDeChamada`, no mesmo commit, o cita
  nominalmente.
- **Sobra o `PublicarExtrairVideo`**, que só chama `chaveDoPacote` (linha 33) — `idVideo + ".zip"`
  puro, que nunca toca o `S3AsyncClient`. Citá-lo como via de acesso ao SDK inverte o fato.

A frase veio copiada da § *O problema* deste ticket, que a escrevia como suspeita ("pelo que se
vê"), em vez de ser refeita contra o código — que é exatamente o que a § *O que entregar*
mandava fazer. A **conclusão não muda**: nenhum `@Incoming` desemboca no SDK, e o
`EnviarVideoUseCase` é borda HTTP como os outros dois. O que muda é o argumento que a sustenta,
e é ele que alguém vai reler daqui a três meses — o dano do 061 outra vez.

O javadoc passou a nomear as **duas idas ao MinIO** (`gravarVideo`, `abrirPacote`) e quem as
chama, em vez do gateway inteiro e de quem o toca. O mapa foi corrigido junto, pelo mesmo motivo.

**Dois ajustes menores, da mesma família.** O caminho medido é `SELECT`, `UPDATE`, publish,
`UPDATE` — o `TransicaoDeVideo.processar` lê antes de transicionar, e a descrição omitia o
`SELECT`. E o `ExtracaoRapidaPelaBordaTest` foi chamado de "o mais longo dos três consumos"; o
teste não é um consumo — ele exercita o mais longo dos três.

**O que ficou como está, e por quê.** O critério 2 pede que nenhuma menção a `@Blocking` sobre
no `Rastro` do `videos`; sobrou uma, e ela é a **negação** ("nenhum deles anota"). É a mesma
forma que o [088](088-rastro-do-notificacao-descreve-recursos-alheios.md) deixou no
`notificacao`, e ela existe para responder à pergunta que o javadoc antigo criou. Dizer que o
consumo não é bloqueante é o conteúdo, não o resíduo.
