# Reconciliação por varredura, em vez de transactional outbox

`EnviarVideoUseCase` toca três sistemas sem transação comum — MinIO, Postgres, RabbitMQ — e
o `videos` morrer entre o `INSERT` e o `publish` deixa uma linha `RECEBIDO` que nunca vira
nada: sem comando não há Extração, sem Extração não há evento, e o `x-delivery-limit` do
[ADR 0001](0001-politica-de-falhas.md) não protege mensagem que jamais entrou na fila. O
mesmo buraco existe do outro lado, entre gravar a transição para `FALHOU` e publicar
`VideoFalhou`. Decidimos **não construir transactional outbox**: a própria tabela `video`
é o outbox, através de duas colunas marcadoras — `comando_publicado_em` e
`falha_publicada_em` — e um `@Scheduled` republica o que está pendente. Fecha as duas
janelas sem tabela nova, sem payload serializado e sem reescrever o dispatcher.

Decidido no [ticket 018](../wayfinder/tickets/018-outbox-transacional.md) e corrigido no
[ticket 027](../wayfinder/tickets/027-melhorias-medidas.md), que descobriu que a marca podia
mentir — ver a primeira consequência abaixo.

## Considered Options

**Transactional outbox canônico** — tabela de mensagens, payload serializado, poller, e todo
o `EventoRabbitDispatcher` passando a ler dela — foi rejeitado pelo preço. Custa cerca de um
dia num projeto de 5,5 semanas solo, competindo diretamente com CI/CD e com o roteiro do
vídeo, e entrega sobre a alternativa escolhida apenas a diferença entre *pelo menos uma vez*
e *exatamente uma vez* — que o ADR 0001 já declarou não ser o regime deste sistema. Todo
consumo aqui é idempotente por construção, então a duplicata que o outbox evitaria é
inofensiva.

**Registrar a limitação e seguir** foi rejeitado porque o enunciado tem como requisito
funcional escrito que *"em caso de picos, o sistema não deve perder uma requisição"*, e um
Vídeo preso em `RECEBIDO` para sempre é, na leitura do usuário, exatamente uma requisição
perdida — ainda que a causa seja crash e não pico. Com o mecanismo custando muito menos que
o outbox, a documentação da limitação deixou de ser a melhor resposta disponível.

**Varredura ingênua por idade** — reenfileirar todo Vídeo parado em `RECEBIDO` há mais de N
minutos, sem coluna marcadora — foi rejeitada por ser errada justamente no cenário do
enunciado: durante um pico, um backlog legítimo de fila deixa Vídeos em `RECEBIDO` com o
comando já publicado, e a varredura os republicaria, dobrando Extrações no pior momento
possível. A marca é o que distingue *publicado e esperando* de *nunca publicado*, e é ela
que reduz N a uma folga contra o crash, em vez de uma aposta sobre o tempo de fila. A
varredura ingênua também não cobre o `VideoFalhou` perdido — uma linha `FALHOU` não tem como
dizer se o evento saiu.

## Consequences

- **`publish-confirms=true` é o que faz a marca significar o que diz.** O default do conector
  RabbitMQ do SmallRye é `false` (verificado no `@ConnectorAttribute` do
  smallrye-reactive-messaging-rabbitmq 4.32.1), e com ele o envio completa **antes** de o
  broker confirmar: `marcarComandoPublicado` gravava marca para mensagem que podia nunca ter
  chegado. O [ticket 025](../wayfinder/tickets/025-carga-conservacao.md) mediu 3 Vídeos em
  `RECEBIDO` com `comando_publicado_em` preenchido e comando nenhum na fila — e como a
  varredura filtra por marca **nula**, ela nunca os reconsiderava: o mecanismo desenhado para
  não perder requisição tinha um buraco exatamente onde alegava não ter. Ligado nos dois canais
  de saída do `videos`. O custo esperado era um round-trip por publicação; medido sob rajada de
  400, ele **não aparece** — 202 com mediana de 11233 ms contra 11504 ms sem confirms, e
  drenagem de 105 s nos dois. A latência ali é fila na borda, não broker.
- **A varredura registra o que republicou, e é assim que a garantia deixou de ser só afirmada.**
  Até o ticket 027 ela era muda, e por isso nenhuma medição jamais a observara agir — o modo
  `mata-videos` do 025 existia para isso e o que produziu foram os defeitos acima.
  `ReconciliarPublicacoesPendentesUseCase` devolve quantos comandos e quantas falhas
  republicou, e o `@Scheduled` em `framework` registra **só** as passadas não vazias (a vazia é
  o caso normal a cada 30 s). Com isso a republicação foi vista de ponta a ponta: um Vídeo em
  `RECEBIDO` sem marca virou `reconciliacao republicou 1 comando(s) e 0 falha(s)` no log e
  chegou a `CONCLUIDO` com a marca gravada.
- **A marca é gravada *depois* do publish, e isso é deliberado.** `INSERT` com marca nula →
  publica → `UPDATE` da marca. Um crash entre o publish e a marca republica o comando: a
  Extração roda duas vezes, o mesmo objeto de Pacote é sobrescrito e o segundo
  `ExtracaoConcluida` é engolido pela guarda de transição do
  [ADR 0002](0002-maquina-de-estados-em-duas-camadas.md). Marcar antes de publicar nunca
  duplicaria, e seria o buraco original com passos extras.
- **Duas réplicas de `videos` varrem ao mesmo tempo, e tudo bem.** Ambas publicam, o consumo
  é idempotente e o `UPDATE ... WHERE marca IS NULL` serializa a marca. `SKIP LOCKED` ou
  eleição de líder pagariam complexidade para evitar uma duplicata que o sistema inteiro foi
  desenhado para tolerar.
- **A varredura é regra de negócio, não infraestrutura.** Vive como use case no `core`
  (`ReconciliarPublicacoesPendentesUseCase`); o `@Scheduled` em `framework` é gatilho burro
  que monta command e chama o controller, como o consumidor de mensagem do ticket 007.
  Publicação de comando não escapa do `core` por uma porta lateral.
- **Um caminho de publicação, dois chamadores.** A varredura chama o mesmo método de gateway
  que o `EnviarVideoUseCase` chama. Caminho próprio de reenvio poderia divergir do original —
  montar a chave do MinIO de outro jeito, por exemplo — e aí o mecanismo de segurança viraria
  fonte de bug.
- **Dois índices parciais, contra o índice único de que o ticket 009 se orgulhava.** A
  consulta roda a cada 30 segundos sobre uma tabela que nunca perde linhas, e o
  `ix_video_dono_recebido` não serve porque o predicado não tem `dono_sub`. O índice da falha
  **precisa** do `estado` no predicado: toda linha não-falhada tem `falha_publicada_em` nula,
  então sem ele o índice parcial indexaria a tabela inteira.
- **O `videos` não tem coluna dizendo que alguém foi notificado.** As marcas se chamam
  `comando_publicado_em` e `falha_publicada_em` porque "esta mensagem saiu daqui" é a única
  coisa que este serviço observa. Se o e-mail chegou é assunto do `notificacao`, e nomear a
  coluna `notificado_em` seria vazar o vocabulário dele para dentro do dono do estado.
