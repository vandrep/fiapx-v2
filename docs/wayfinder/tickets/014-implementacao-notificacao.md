# Implementação do serviço notificacao

- id: 014
- label: wayfinder:task
- status: fechado
- assignee: vandrep
- bloqueado-por: 007

## Question

O mais fino dos três. O contrato de mensagens (ticket 007) deixou o `notificacao` sem banco,
sem cliente HTTP e sem contato com o Keycloak: ele consome `VideoFalhou`, renderiza um
template e manda SMTP. Tudo que ele precisa saber vem no payload.

Implementar, test-first, conforme
[`docs/contratos/mensagens.md`](../../contratos/mensagens.md) e o `AGENTS.md` do template:

- Consumidor de `notificacao.video-falhou` em `framework.dispatcher`, chamando controller e
  use case — sem regra no consumidor.
- Tradução de `codigoMotivo` para a frase em português que o usuário lê. Esta é a única
  parte do sistema que escolhe esse texto.
- Template do e-mail: assunto, corpo, e o que aparece além do `nomeArquivoOriginal`.
- Envio SMTP (`quarkus-mailer`) contra MailHog no Compose e o que roda em `@QuarkusTest`.
- `max-outstanding-messages=10`, `failure-strategy=requeue`, DLQ terminal `notificacao.dlq`.
- Health check, para o `depends_on: service_healthy` do Compose.
- Regra nova no `ArchitectureConstraintsTest`: `@Incoming`/`@Outgoing` só em `framework`.

O e-mail é **pelo menos uma vez** (ADR 0001) — o serviço não tenta deduplicar, e isso é
deliberado.

## Resolução

21 testes verdes, **14 sem Docker** (`MotivoFalhaTest`, `EnviarNotificacaoDeFalhaUseCaseTest`,
`ArchitectureConstraintsTest`); o `CucumberTest` precisa de Docker mesmo sem exercitar
RabbitMQ de fato no cenário, porque o `@QuarkusTest` sobe o app inteiro e o conector
RabbitMQ liga o Dev Service no boot. Ele chama o `NotificacaoController` direto — o
mesmo papel de fronteira que um `Resource` cumpre num serviço com borda HTTP — e verifica o
e-mail "enviado" via `io.quarkus.mailer.MockMailbox`, o mock automático do quarkus-mailer
fora de `%prod`. Sem isso o teste precisaria de um Dev Service de MailHog, que não existe.

**`DESCONHECIDO` chega aqui como valor legítimo, não só em teoria**: o `videos` (ticket 017)
publica `motivo.name()` no `VideoFalhou.codigoMotivo`, e `motivo` já passou pelo
`MotivoFalha.doCodigo` tolerante dele — então um `extracao` mais novo que o `videos` ainda
não conhece chega ao `notificacao` como a string `"DESCONHECIDO"`, não como um código
qualquer. O `MotivoFalha` daqui tem seu próprio `DESCONHECIDO` com frase genérica, e o
cenário "motivo desconhecido" do BDD cobre exatamente essa cadeia, não um código inventado.

**A tradução mora inteira no `core`**: `MotivoFalha.paraFrase()` e o record
`NotificacaoDeFalha` (assunto + corpo) são POJOs — nenhuma anotação de framework, nenhum
`Uni`. O `EmailGateway` do `core` só sabe `enviar(destinatário, assunto, corpo)`; quem
conhece MailHog, `Mail.withText` e retry é o `framework.service`.

**`donoSub` do contrato não entra no e-mail nem no use case** — é chave de suporte, e um
identificador OIDC não diz nada ao usuário. Fica só num log estruturado no
`VideoFalhouConsumer` (framework), para correlacionar com um chamado de suporte sem sujar o
`core` com um dado que ele não usa.

**O texto do e-mail usa acentos; comentários e nomes de código, não** — divergência
deliberada do resto do repositório. Comentário e nome de variável são para quem lê código;
o corpo do e-mail é para um usuário final, e "nao e suportado" leria mal numa caixa de
entrada. `MotivoFalha.paraFrase()` e o template de `NotificacaoDeFalha.corpo()` são as únicas
strings do repositório com acentuação completa fora de prosa em Markdown.

**Achado real do `io.quarkus.mailer.MockMailbox`**: tem três métodos "sent to" — o mais
óbvio, `getMessagesSentTo`, está `@Deprecated(forRemoval)`; `getMailMessagesSentTo` devolve
`io.vertx.ext.mail.MailMessage`, não o `Mail` do Quarkus. O correto para asserção de
assunto/corpo é `getMailsSentTo`, que devolve `List<Mail>`.

**Sem `@Blocking` no consumidor** — ao contrário do `extracao` (ffmpeg via `ProcessBuilder`,
bloqueante), o envio por `quarkus-mailer` é reativo ponta a ponta (Vert.x Mail Client), então
`VideoFalhouConsumer` fica na event loop.

Regra nova no `ArchitectureConstraintsTest`, aplicada às três cópias (`@Incoming`/`@Outgoing`
só em `framework`) — só essa regra: o `RestMulti` do ticket 016 e o `ProcessBuilder` do
ticket 015 não entraram aqui, porque este ticket não dependia deles e as cópias só precisam
ficar idênticas dentro do próprio branch; a reconciliação entre os quatro branches de
implementação acontece no merge.

Topologia (exchange `fiapx.eventos`, fila quorum `notificacao.video-falhou` com
`x-delivery-limit=3`, DLX `notificacao.dlx`, `notificacao.dlq` terminal e classic, prefetch
10) verificada de ponta a ponta contra RabbitMQ real via management API em `quarkus dev`, não
só assumida a partir da config — mesmo precedente dos tickets 015 e 017.
