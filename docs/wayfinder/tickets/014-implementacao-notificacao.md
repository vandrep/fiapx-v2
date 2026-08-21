# Implementação do serviço notificacao

- id: 014
- label: wayfinder:task
- status: aberto
- assignee:
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
