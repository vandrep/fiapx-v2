# O `Rastro` do `videos` credita os saltos de thread a dois mecanismos que ele não usa

- id: 090
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] A frase dos saltos de thread no `Rastro` do `videos` descreve o que a cadeia de
      `naMensagem` **deste** serviço faz, verificado e não suposto
- [ ] Nenhuma menção a `@Blocking` sobrou no `Rastro` do `videos` sem que o `videos` use a
      anotação
- [ ] O papel do contexto duplicado do Vert.x continua dito, e a § *Onde o escopo pode atravessar
      thread* continua fazendo sentido depois da edição
- [ ] As cópias do `extracao` e do `notificacao` não foram tocadas para convergir
- [ ] Nenhum span, nome de span ou atributo mudou
- [ ] `./mvnw test` verde a partir da raiz
