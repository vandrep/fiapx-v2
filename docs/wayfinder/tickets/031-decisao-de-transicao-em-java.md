# A decisão de transição roda em Java, no caminho de produção

- id: 031
- label: wayfinder:task
- status: fechado
- assignee:
- bloqueado-por: 029

## Question

O [ADR 0002](../../adr/0002-maquina-de-estados-em-duas-camadas.md) diz que `EstadoVideo`
responde *"esta transição é legal?"* e que o `UPDATE` condicional responde *"fui eu quem de
fato mudou a linha?"*, e que são perguntas diferentes. **Em produção a primeira nunca é
feita.** Metade do ADR nunca rodou.

O levantamento, feito sobre `develop @ 6128f33`:

- `EstadoVideo.predecessores()` tem três chamadores em `src/main`, todos no
  `VideoDataSourceAdapter` (linhas 89, 104 e 118). É a única porta de produção para o grafo.
- `Video.marcaComoIniciada`, `marcaComoConcluida`, `marcaComoFalha`, `EstadoVideo.transitaPara`
  e `EstadoVideo.terminal` têm **zero** chamadores em `src/main`. Só testes e o
  `GatewaysEmMemoria` os alcançam.
- O use case não carrega o Vídeo: `ProcessarExtracaoIniciadaUseCase` passa o **id** ao gateway,
  que emite o `UPDATE`. Não há instância de `Video` no caminho de escrita.
- O estado de um Vídeo, em produção, só é atribuído em `novo` (que põe `RECEBIDO`) e em
  `reconstituir` (que copia a coluna). Toda transição acontece dentro do Postgres.

A consequência é que **a suíte de use case inteira valida uma implementação que não embarca**.
Não é código morto qualquer: é a regra que decide se o Dono recebe e-mail.

Correção a um diagnóstico anterior: o grafo **não** está declarado duas vezes.
`transitaPara(d)` termina em `d.predecessores().contains(this)` — existe um `switch` só, e as
duas portas leem dele. O que está declarado duas vezes é **quais campos cada desfecho
escreve**: a lista dos `marcaComo*` e a lista do `SET` do `UPDATE`. Hoje elas conferem, campo
a campo; nada no build as compara.

## A forma decidida

O use case carrega o Vídeo, pergunta à entidade, e o `UPDATE` condicional continua sendo quem
autoriza publicar:

1. `SELECT` do Vídeo pelo id.
2. `video.marcaComoFalha(...)` — `false` significa reentrega fora de ordem: `ack`, sem tocar o
   banco.
3. No `true`, o mesmo `UPDATE ... where id = ? and estado in ?` de hoje.
4. O boolean **do `UPDATE`** é o que autoriza publicar `VideoFalhou`.

Com o esquema fechado, a coluna `estado` já **é** a coluna de versão: o `WHERE` é um
compare-and-swap, e a guarda do [ADR 0001](../../adr/0001-politica-de-falhas.md) não afrouxa.
Foi por isso que esta forma fecha e o read-modify-write clássico não fecharia.

Leitura suja conferida: percorridos os quatro estados de origem, todo `false` da entidade
corresponde a um `IN` que também não casaria. A entidade pode dizer "legal" e o `UPDATE` casar
zero linhas — caso já tratado pelo boolean. O inverso não existe.

## As três decisões que vieram junto

**O `SELECT` mora no use case, e a posse deixa de ser estrutural.** O `VideoGateway` não tem
busca por id sozinho de propósito ([009](009-modelo-dominio-videos.md)): *"não há como pedir um
Vídeo sem dizer de quem"*. O caminho de mensageria não tem Dono para informar. Entra um
`buscarPorId(UUID)` com Javadoc dizendo a que caminho serve, mais uma asserção no
`ArchitectureConstraintsTest` — **nas três cópias** — proibindo `Resource` e controller HTTP de
chamá-lo. A guarda desce de estrutural para verificada, e isso é preço, não detalhe.

**Terminal para o outro terminal devolve `false` com log, nunca exceção.** O ADR 0002
classificou `FALHOU → CONCLUIDO` como bug porque nunca o viu acontecer por rede. Ele é
alcançável por operação: a conexão com o broker cai enquanto o ffmpeg ainda roda, a mensagem é
reentregue, esgota o limite, a DLQ sintetiza `ExtracaoFalhou`, o Vídeo vai a `FALHOU` — e a
réplica original, viva o tempo todo, termina e publica `ExtracaoConcluida`. Primeiro terminal
vence é a única regra sã sob entrega ao-menos-uma-vez com um consumidor de DLQ sintetizando
desfecho, e é o que produção já faz hoje. Ligar a exceção mandaria uma corrida de rede para a
DLQ do `videos`. O log existe porque a corrida é rara: se ficar frequente, queremos saber antes
do Dono.

**As três transições passam a devolver `boolean`.** `marcarFalha` devolve `Optional<Video>` só
porque o use case não tinha o objeto para publicar `VideoFalhou`. Com o `SELECT` na frente ele
tem, e `dono`, `email` e `nome` são imutáveis. Some a busca extra dentro da transação do
adapter.

## Custo aceito

Um `SELECT` a mais por evento, nos três desfechos — o consumo dobra as idas ao banco. Aceito e
medido depois, com o instrumento do [026](026-linearidade-horizontal.md). **Sem exceção para a
`Iniciada`** por ser barata: assimetria entre os três desfechos é a origem da próxima
divergência.

## Condição de aceite

- Nenhum método de `Video` ou de `EstadoVideo` sem chamador em `src/main`.
- `VideoDataSourceAdapterTest` assertando, campo a campo, o Vídeo resultante de cada transição
  contra Postgres — é o que impede a lista do `SET` de divergir da lista da entidade em
  silêncio. Hoje esse par não é comparado por nada.
- Um teste do caminho terminal→terminal provando `false` e não exceção.
- `ArchitectureConstraintsTest` idêntico nas três cópias; `scripts/verifica-testes-arquiteturais.sh`
  passa na fase `validate`.
- Emenda no ADR 0002 (§ *A entidade entra no caminho de produção*) e ajuste do Javadoc de
  `VideoGateway`, que hoje descreve a assinatura antiga.
