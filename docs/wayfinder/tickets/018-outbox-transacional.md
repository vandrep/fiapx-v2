# Transactional outbox no videos, ou conviver com o Vídeo órfão

- id: 018
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Question

O ticket 009 expôs um buraco e recusou-se a tapá-lo de afogadilho. `EnviarVideoUseCase` toca
três sistemas sem transação comum — MinIO, Postgres, RabbitMQ — na ordem objeto → linha →
publicação. Se o `videos` morrer entre o `INSERT` e o `publish`, sobra uma linha `RECEBIDO`
que **nunca vira nada**: sem comando não há extração, sem extração não há evento, e o
`x-delivery-limit` não protege uma mensagem que jamais entrou na fila. O usuário vê
`RECEBIDO` para sempre, e o `@Retry` do ADR 0001 só cobre a falha transitória do publish,
não o processo morto.

O mesmo buraco existe do outro lado: `RegistrarExtracaoFalhouUseCase` grava a transição e
publica `VideoFalhou` — morrer entre os dois perde a notificação de uma falha que já foi
registrada.

A decidir:

- **Adotar outbox transacional, ou registrar a limitação e seguir?** O custo é uma tabela, um
  poller e a mudança do `EventoRabbitDispatcher` para ler dela — provavelmente um dia. O
  benefício é fechar uma janela de milissegundos que a demo não produz, mas que a banca pode
  perguntar.
- Se **sim**: a tabela entra no `init.sql` do ticket 009 e o `%prod.validate` passa a exigi-la;
  o poller é `@Scheduled` (`quarkus-scheduler`) ou o Quarkus tem algo melhor? Como fica o
  teste de que a mensagem sai **uma** vez? A entrega vira *pelo menos uma vez*, o que já é o
  regime do ADR 0001 — o consumo é idempotente, então duplicar é inofensivo.
- Se **não**: em que documento a limitação fica registrada, e com que redação. O ADR 0001 é o
  lugar natural, como um "Consequências conhecidas".
- Existe meio-termo barato? Uma varredura periódica que reenfileira Vídeos parados em
  `RECEBIDO` há mais de N minutos resolve o caso do upload sem tabela nova — mas não resolve
  o `VideoFalhou` perdido.

**Contexto de prazo**: 5,5 semanas solo, e este ticket concorre diretamente com CI/CD e com o
roteiro do vídeo. Uma decisão defensável em duas frases vale mais aqui do que a solução
tecnicamente superior.

## Resolução

**Nem outbox transacional, nem conviver com o órfão: a tabela `video` vira o outbox.** Duas
colunas marcadoras — `comando_publicado_em` e `falha_publicada_em` — mais um `@Scheduled` que
republica o pendente fecham as duas janelas sem tabela nova, sem payload serializado e sem
reescrever o `EventoRabbitDispatcher`. Registrado no
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md).

### Por que não a resposta que o ticket antecipava

O ticket ofereceu três caminhos e a decisão recusou os três. **Outbox canônico** custa o dia
que compete com CI/CD, e entrega sobre a alternativa apenas a diferença entre *pelo menos uma
vez* e *exatamente uma vez* — regime que o ADR 0001 já descartou explicitamente, porque todo
consumo aqui é idempotente. **Registrar a limitação e seguir** deixou de ser a melhor resposta
disponível assim que o mecanismo ficou barato: o enunciado tem como requisito funcional
escrito que *"em caso de picos, o sistema não deve perder uma requisição"*, e um Vídeo preso
em `RECEBIDO` para sempre é uma requisição perdida na leitura do usuário, ainda que a causa
seja crash e não pico. E o **meio-termo que o próprio ticket sugeriu** — varredura por idade,
sem marca — não é só incompleto (não cobre o `VideoFalhou` perdido): ele é *errado* no cenário
do enunciado. Num pico, backlog legítimo de fila deixa Vídeos em `RECEBIDO` com o comando já
publicado, e a varredura por idade os republicaria, dobrando Extrações no pior momento
possível. A marca é o que transforma N de aposta sobre o tempo de fila em folga contra o
crash.

### Os dois buracos não pesam igual, mas o mecanismo cobre os dois de graça

O `ExtrairVideo` perdido é visível e silencioso; o `VideoFalhou` perdido deixa o Vídeo
`FALHOU` correto na API, só sem e-mail — e o e-mail já era o elo assumidamente frouxo do ADR
0001. A assimetria justificaria escolher um mecanismo pior, não deixar cobertura na mesa: como
a mesma varredura cobre `FALHOU` + `falha_publicada_em IS NULL` pelo mesmo preço, cobre.

### O que fica decidido

- **Ordem**: `INSERT` com marca nula → publica → `UPDATE` da marca. Marcar antes de publicar
  nunca duplicaria e não resolveria nada — é o buraco original com passos extras.
- **Camadas**: `ReconciliarPublicacoesPendentesUseCase` no `core`; o `@Scheduled` em
  `framework` é gatilho burro que monta command e chama controller, análogo ao consumidor de
  mensagem do ticket 007. A varredura chama **o mesmo** método de gateway que o
  `EnviarVideoUseCase` chama — caminho próprio de reenvio poderia divergir do original e o
  mecanismo de segurança viraria fonte de bug.
- **Parâmetros**: `every=30s`, idade mínima de **1 minuto** (folga contra ler a própria linha
  entre o `INSERT` e a marca de uma execução em curso), lote de **100** por passada,
  `ORDER BY recebido_em`.
- **Concorrência**: duas réplicas varrendo é aceito. Ambas publicam, o consumo é idempotente,
  e o `UPDATE ... WHERE marca IS NULL` serializa a marca. `SKIP LOCKED` e eleição de líder
  pagariam complexidade contra uma duplicata que o sistema foi desenhado para tolerar.
- **Nomes**: `comando_publicado_em` e `falha_publicada_em`. **Não** `notificado_em` — o
  `videos` não sabe se o e-mail chegou, e nomear assim vazaria vocabulário do `notificacao`
  para dentro do dono do estado, exatamente o acoplamento que o ticket 007 comprou caro ao
  proibir `extracao` e `notificacao` de se falarem.
- **Índices**: dois parciais, contra o índice único de que o 009 se orgulhava — a varredura
  roda a cada 30s sobre tabela que nunca perde linhas e o `ix_video_dono_recebido` não serve
  (o predicado não tem `dono_sub`). O da falha **precisa** do `estado` no predicado: sem ele
  indexaria a tabela inteira, já que toda linha não-falhada tem a coluna nula.
- **`CONTEXT.md` não muda.** "Reconciliação" é maquinário interno de um serviço, e o glossário
  já declara as transições idempotentes — que é o invariante do qual isto tudo depende.

### Os quatro testes

No `core`, sem broker:

1. Linha `RECEBIDO` com `comando_publicado_em` nulo e velha o bastante é republicada e
   marcada; **a segunda passada não publica nada**. É o teste canônico: prova que a varredura
   fecha a janela *e* não vira máquina de duplicar.
2. Linha `RECEBIDO` já marcada não é tocada, **por mais velha que seja** — prova que backlog
   de pico não gera Extração dobrada.
3. e 4. Os dois espelhados para `FALHOU` / `falha_publicada_em`.

O teste que não vale a pena é o de crash real entre publish e marca: exige matar processo, e o
comportamento nesse caso é o que (1) já exercita.

### Onde o código pousa

No **ticket 017**, que este ticket desbloqueia — mesmo serviço, mesmo gateway, mesma sessão de
trabalho. Um ticket separado não poderia ser feito sem abrir o 017 junto. O `init.sql` do 009
já ganhou as duas colunas e os dois índices nesta passada.

### Descoberto aqui, fora deste ticket

A tabela `video` **nunca perde linhas**, mas o ticket 011 deu 7 dias de ciclo de vida ao
MinIO: no oitavo dia existe um Vídeo `CONCLUIDO`, listado como tal, cujo `chave_pacote` aponta
para um objeto que não existe mais. Virou o **ticket 019**.
