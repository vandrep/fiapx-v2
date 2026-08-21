# Contrato de mensagens entre videos, extracao e notificacao

- id: 007
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por: 003, 006, 010

## Question

Sem módulo `shared`, o contrato de mensagens é a fronteira real entre os três serviços —
e cada um declara sua própria cópia. Isso só funciona se o contrato for decidido
explicitamente e documentado num lugar só.

A decidir:

- Topologia no RabbitMQ: exchanges, filas, routing keys, DLQs, e quem declara o quê.
- Quais mensagens existem: o comando de extração e os eventos de progresso, conclusão e
  falha. Nomes canônicos, em português, alinhados ao `CONTEXT.md`.
- Payload de cada mensagem: campos, tipos, e o que **não** entra (o vídeo em si nunca
  trafega — só a chave do objeto no MinIO).
- Identidade e correlação: o que amarra evento a Vídeo, e como isso serve de chave de
  idempotência quando o RabbitMQ redelivera.
- Como o evento de **falha definitiva** (o que aciona o e-mail) se distingue de uma falha
  de tentativa — o usuário não pode receber três e-mails.
- Versionamento: o que acontece quando um campo precisa mudar, dado que há três cópias do
  contrato.
- Onde o contrato vive documentado para as três cópias não divergirem.

Este ticket gradua boa parte da névoa de implementação — espere criar tickets novos ao
fechá-lo.

## Resolução

Contrato completo em [`docs/contratos/mensagens.md`](../../contratos/mensagens.md);
vocabulário das mensagens acrescentado ao [`CONTEXT.md`](../../../CONTEXT.md). Sem ADR — o
trade-off duro já está registrado no [ADR 0001](../../adr/0001-politica-de-falhas.md), e
isto é especificação viva, não decisão surpreendente.

**Cinco mensagens**, comando no imperativo e evento no particípio: `ExtrairVideo`
(`videos`→`extracao`); `ExtracaoIniciada`, `ExtracaoConcluida`, `ExtracaoFalhou`
(`extracao`→`videos`); `VideoFalhou` (`videos`→`notificacao`). **`extracao` e `notificacao`
nunca se falam** — a mediação por `videos` é o que dá unicidade ao e-mail sem dar banco ao
serviço mais fino. `ExtracaoIniciada` não é opcional: o `CONTEXT.md` define "na fila" como
`RECEBIDO`, então o `videos` não pode marcar `PROCESSANDO` ao publicar o comando.

**Sem envelope** — o tipo da mensagem existe só na routing key e na fila, o que empurra para
uma fila por tipo, casando 1:1 com um canal SmallRye e um `record`. Dois exchanges topic
(`fiapx.comandos`, `fiapx.eventos`), filas prefixadas pelo consumidor, topologia declarada
pelo conector (o `definitions.json` só tem policy e usuários, porque os Dev Services sobem
broker limpo em teste).

**DLQs assimétricas**: dedicada onde há consumidor (`extracao.extrair.dlq`), compartilhada
por serviço onde é terminal (`videos.dlq`, `notificacao.dlq`).

**O `extracao` não conhece a convenção de chaves do MinIO**: recebe `chaveVideo` e
`chaveDestinoPacote` prontos no comando. O formato fica inteiro dentro do `videos`, e mudá-lo
depois (ticket 011) não toca dois serviços.

**Motivo da falha é código estável**, não frase: `ARQUIVO_INVALIDO`, `FORMATO_NAO_SUPORTADO`,
`SEM_FLUXO_DE_VIDEO`, `TENTATIVAS_ESGOTADAS`, mais um `detalheTecnico` só para log — o stderr
do ffmpeg nunca chega ao usuário. `TENTATIVAS_ESGOTADAS` existe porque o consumidor da DLQ
não sabe *por que* falhou, só que o `x-delivery-limit` estourou. Falha permanente publica
direto e dá ack; transitória esgotada atravessa a DLQ. Dois sítios de publicação, um só use
case.

**Prefetch explícito e obrigatório** em todo canal: `extracao`=1, `videos`=20,
`notificacao`=10.

**Camadas**: o template não cobre mensageria (`framework.dispatcher` e
`core.interfaces.sender` estão documentados no `AGENTS.md` dele, mas vazios — sem exemplo e
sem regra de teste). Decidido espelhar a borda HTTP: consumidor é análogo a `Resource`, vive
em `framework.dispatcher`, chama controller, chama use case; os `record` do contrato ficam lá
e nunca cruzam para o `core`. Cada serviço acrescenta ao seu `ArchitectureConstraintsTest` a
regra de que `@Incoming`/`@Outgoing` só aparecem em `framework`.

**Versionamento**: só aditivo + tolerant reader (`@JsonIgnoreProperties(ignoreUnknown = true)`),
sem campo `versao`; mudança incompatível vira routing key nova.

**Restrições que este ticket impõe a outros:**

- **Ticket 009**: `videos` precisa persistir `emailDono` e `nomeArquivoOriginal`, senão
  `VideoFalhou` não fecha.
- **Realm do Keycloak** (ainda na névoa): o token precisa emitir o claim `email`.
- **Ticket 011** segue dono do *formato* da chave de objeto; o contrato só diz que ela trafega.
