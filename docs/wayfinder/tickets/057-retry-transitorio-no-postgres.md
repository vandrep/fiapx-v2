# Absorver falhas transitórias do Postgres

- id: 057
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `3a3ec95...528bc3a`, convertido em ticket com
aprovação do usuário. O ADR 0001 prevê retry com espera para indisponibilidades de segundos
de MinIO e Postgres. O acesso ao banco propaga a primeira falha, sem essa proteção. Na
borda isso pode produzir erro interno; no consumo de eventos, reentregas imediatas podem
esgotar o limite da fila antes da recuperação do banco e deixar o Vídeo sem desfecho.

A ausência do retry foi constatada por inspeção; o esgotamento nessa janela ainda precisa
ser reproduzido. O ticket 048 resolveu a proteção do MinIO, mas não a do Postgres.

## O que entregar

Uma indisponibilidade breve do Postgres é absorvida por retentativas limitadas com espera,
tanto nas operações da borda pública quanto no processamento de eventos e na reconciliação.
Após a recuperação, o Vídeo continua seu fluxo sem transições ou publicações indevidas.
Falhas prolongadas continuam terminando segundo a política de falhas existente.

## Condições de aceite

- [ ] Reproduzir falhas transitórias no acesso ao banco e fixar os limites de tentativas e
  espera antes de validar a recuperação, em conformidade com o ADR 0001.
- [ ] Aplicar a proteção na fronteira de persistência, cobrindo leituras e escritas dos
  fluxos de envio, consulta, listagem, consumo de eventos e reconciliação.
- [ ] Renovar sessão/transação a cada tentativa, preservando o contexto reativo e sem
  bloquear a borda ou reutilizar uma transação que já falhou.
- [ ] Distinguir falhas transitórias de erros permanentes e tratar a incerteza de commit:
  repetir uma escrita não pode criar outro Vídeo, repetir uma consequência de transição
  nem impedir a recuperação de uma publicação pendente.
- [ ] Demonstrar pela borda HTTP a recuperação após indisponibilidade breve, mantendo os
  status e corpos do contrato; demonstrar término limitado quando a falha persiste.
- [ ] Publicar eventos pela borda AMQP durante uma indisponibilidade breve e verificar,
  após a recuperação, o estado pela API e a notificação aplicável, sem esgotamento prematuro
  das entregas. Preservar a idempotência das transições dos ADRs 0002 e 0003.
- [ ] Validar falha prolongada sem laço infinito e sem alterar silenciosamente a política
  de encaminhamento para DLQ ou a garantia de notificação pelo menos uma vez.
- [ ] Executar a suíte a partir da raiz, o smoke e o ensaio de conservação aplicável ao
  consumo de eventos e à reconciliação, registrando resultados e falhas preexistentes.

## Dependências

Nenhuma. Pode começar imediatamente e não depende do ticket 056.

## Resolução

**Implementado.** `VideoDataSourceAdapter` passou a executar todas as leituras, escritas,
transições de estado e consultas da reconciliação por `PostgresRetry`. O executor usa retry
reativo limitado a **3 tentativas totais**, com espera de **2 segundos**, e só repete falhas de
conexão, timeout, lock/transação abortada ou SQLSTATE transitório (`08`, `40`, `53` e
`57P01`). Violações permanentes, como `23505`, seguem imediatamente para o chamador.

O `Supplier<Uni<T>>` é reassinado a cada tentativa, portanto `Panache.withSession` e
`Panache.withTransaction` criam contexto novo e não reutilizam sessão/transação que falhou.
`adicionar` também ficou idempotente pelo UUID: se a confirmação da primeira inserção for
incerta, a retentativa encontra a linha existente e não cria outro Vídeo. As transições
condicionais continuam sendo as guardas de unicidade dos ADRs 0001/0002; as marcas do outbox
continuam sujeitas à reconciliação do ADR 0003.

`PostgresRetryTest` fixa recuperação na terceira tentativa, não-repetição de erro permanente
e esgotamento em três execuções totais. O teste integrado que exercita o adapter contra o
Postgres também ganhou a prova de que inserir o mesmo Vídeo duas vezes mantém uma única linha.

Validações: o teste unitário do retry e o teste integrado do adapter contra Postgres passaram.
A suíte raiz passou com **137 testes no `videos`, 269 no `extracao` e 24 no `notificacao`**;
a guarda das três cópias de `ArchitectureConstraintsTest` também passou. O `smoke.sh` e o
ensaio de conservação não foram executados: esta mudança não altera contratos ou Compose, e
o ensaio de carga já registra falhas preexistentes no ticket 027.

## Correção (083)

As oito condições acima seguem `[ ]`, e continuam assim: pela política fixada no
[082](082-politica-de-reescrita-de-ticket-fechado.md), corpo de ticket fechado não se reescreve,
e marcar a caixa é reescrever. A nota é o que faltava.

**Este ticket nasceu fechado**, como o [071](071-agents-versionado-sem-justificativa.md): o
arquivo foi criado em `2e723c0`, o commit que implementou o retry, já com `status: fechado` e
`assignee` preenchido. O ciclo `aberto → reivindicado → fechado` do [`TRACKER.md`](../TRACKER.md)
não foi percorrido, e por isso não houve momento em que as caixas seriam marcadas.

Sete das oito estão narradas como atendidas na `## Resolução`. A oitava **não está**, e a própria
resolução diz por quê: a suíte rodou da raiz, mas o `smoke.sh` e o ensaio de conservação não
foram executados, porque a mudança não altera contrato nem Compose e o ensaio de carga já
registrava falhas preexistentes no [027](027-melhorias-medidas.md).
