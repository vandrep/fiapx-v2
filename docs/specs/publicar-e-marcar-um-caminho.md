# Publicar-e-marcar, um caminho só

- id: 001
- label: ready-for-agent
- status: aberto

## Problem Statement

O `videos` publica dois eventos de saída — o comando `ExtrairVideo` e o evento `VideoFalhou`
— sempre seguindo a mesma sequência: enviar pela mensageria e, só depois, gravar a marca que
o [ADR 0003](../adr/0003-reconciliacao-por-varredura.md) usa como outbox
(`comando_publicado_em` / `falha_publicada_em`). Essa sequência está implementada, palavra por
palavra, em três lugares independentes: `EnviarVideoUseCase` (o caminho normal do
`ExtrairVideo`), `ProcessarExtracaoFalhouUseCase` (o caminho normal do `VideoFalhou`) e
`ReconciliarPublicacoesPendentesUseCase` (a varredura de reconciliação, para os dois).

O próprio ADR 0003 já declara a intenção de que exista **um caminho de publicação, dois
chamadores** — hoje o código promete isso na consequência documentada, mas não entrega: a
varredura reimplementa a sequência em vez de reutilizá-la. Isso significa que qualquer mudança
futura na política de publicação (uma métrica, um log, um ajuste de retry específico do
publish) precisa ser lembrada e replicada em três lugares — e nada no repositório avisa se
alguém esquecer um deles. É a duplicação real confirmada no relatório de arquitetura de
2026-08-30 (candidato 1, força `Strong`): os três testes de use case correspondentes já
reasserem exatamente o mesmo comportamento (`idsEnviados`, `comandoPublicadoEm`/
`falhaPublicadaEm` preenchidos) três vezes.

## Solution

Extrair a sequência "enviar → marcar" para dois módulos pequenos e concretos, um por tipo de
evento, vivendo ao lado dos use cases que os chamam (`core/usecases/video/`), sem sufixo
`UseCase` — porque não são pontos de entrada chamados por controller, e o guard
`useCasesDevemUsarMetodoExecutarECompletableFuture` do `ArchitectureConstraintsTest` filtra
justamente pelo sufixo `UseCase.java`, então não faz sentido herdar uma obrigação de forma
(`executar(...)`) que não corresponde ao papel da classe.

Os três chamadores atuais passam a delegar a esses dois módulos em vez de reimplementar a
sequência. Nenhuma interface nova é introduzida: existe exatamente uma implementação real
possível (o par enviar+marcar não varia por ambiente), e as dependências que cada módulo usa
(`ExtracaoSender`/`ArquivoGateway`/`VideoGateway` de um lado, `NotificacaoSender`/
`VideoGateway` do outro) já são interfaces do `core` — a extração não cria um seam, só move
orquestração que já existia para um lugar único.

## User Stories

1. Como desenvolvedor mantendo o serviço `videos`, quero uma única implementação da sequência
   "enviar `ExtrairVideo` e marcar `comando_publicado_em`", para que uma mudança nessa política
   não precise ser replicada manualmente em `EnviarVideoUseCase` e em
   `ReconciliarPublicacoesPendentesUseCase`.
2. Como desenvolvedor mantendo o serviço `videos`, quero uma única implementação da sequência
   "enviar `VideoFalhou` e marcar `falha_publicada_em`", para que uma mudança nessa política não
   precise ser replicada manualmente em `ProcessarExtracaoFalhouUseCase` e em
   `ReconciliarPublicacoesPendentesUseCase`.
3. Como desenvolvedor lendo `EnviarVideoUseCase` pela primeira vez, quero que a classe só
   orquestre upload e persistência do Vídeo, delegando a publicação do comando a um
   colaborador nomeado, para que o fluxo principal fique legível sem entender os detalhes do
   outbox do ADR 0003.
4. Como desenvolvedor lendo `ProcessarExtracaoFalhouUseCase` pela primeira vez, quero que a
   classe só orquestre a guarda de unicidade da transição de estado, delegando a publicação da
   notificação de falha a um colaborador nomeado, pelo mesmo motivo.
5. Como desenvolvedor lendo `ReconciliarPublicacoesPendentesUseCase` pela primeira vez, quero
   que a varredura chame os mesmos dois colaboradores que os caminhos normais chamam, para que
   fique visível no código — não só documentado em prosa no ADR 0003 — que existe um caminho
   de publicação só.
6. Como desenvolvedor escrevendo um teste unitário para a sequência "enviar e marcar" do
   `ExtrairVideo`, quero testá-la isolada da lógica de upload/persistência de
   `EnviarVideoUseCase` e do laço sequencial de `ReconciliarPublicacoesPendentesUseCase`, para
   que o teste não precise montar cenários dos dois use cases só para cobrir essa sequência.
7. Como desenvolvedor escrevendo um teste unitário para a sequência "enviar e marcar" do
   `VideoFalhou`, quero o mesmo isolamento em relação a `ProcessarExtracaoFalhouUseCase` e
   `ReconciliarPublicacoesPendentesUseCase`.
8. Como usuário do sistema enviando um Vídeo, quero que o comportamento observável de envio
   (o `202`, o Vídeo aparecer como `RECEBIDO`, a Extração eventualmente rodar) continue idêntico
   ao de hoje — a extração é um refactor interno, não uma mudança de contrato.
9. Como usuário do sistema cujo Vídeo falha definitivamente, quero continuar recebendo
   exatamente um e-mail de notificação, preservando a garantia de unicidade do
   [ADR 0001](../adr/0001-politica-de-falhas.md) e do
   [ADR 0002](../adr/0002-maquina-de-estados-em-duas-camadas.md) — a extração não pode
   enfraquecer a guarda "só a transição que de fato mudou a linha publica".
10. Como operador confiando na reconciliação por varredura, quero que
    `ReconciliarPublicacoesPendentesUseCase` continue publicando comandos e falhas pendentes
    exatamente como hoje (mesmo filtro de marca nula, mesma folga contra crash, mesma execução
    sequencial dentro de uma passada) — a extração não pode alterar o comportamento do
    [ADR 0003](../adr/0003-reconciliacao-por-varredura.md), só remover a duplicação de código
    que o implementa.
11. Como revisor de código avaliando esta mudança, quero que o diff mostre claramente que
    `EnviarVideoUseCase`, `ProcessarExtracaoFalhouUseCase` e
    `ReconciliarPublicacoesPendentesUseCase` perderam dependências diretas de mensageria
    (`ExtracaoSender`/`NotificacaoSender`) em favor dos dois módulos novos, para confirmar que a
    duplicação foi removida na origem, não só escondida atrás de mais uma camada.
12. Como futuro leitor do `ArchitectureConstraintsTest`, quero que os dois módulos novos não
    sejam confundidos com use cases de entrada (chamados por controller), para que o guard de
    sufixo `UseCase` continue significando "ponto de entrada orquestrado a partir de HTTP ou
    mensageria", não "qualquer classe do pacote usecases".

## Implementation Decisions

- **Dois módulos, não um.** As duas sequências têm dependências disjuntas: publicar
  `ExtrairVideo` usa `ArquivoGateway.chaveDoPacote` + `ExtracaoSender` +
  `VideoGateway.marcarComandoPublicado`; publicar `VideoFalhou` usa `NotificacaoSender` +
  `VideoGateway.marcarFalhaPublicada`. Nenhum chamador precisa das duas ao mesmo tempo. Um
  módulo único com quatro dependências no construtor teria interface mais larga do que
  qualquer chamador de fato usa.
- **`PublicarExtrairVideo`**, em `core/usecases/video/`. Construtor:
  `(ArquivoGateway, ExtracaoSender, VideoGateway)`. Método público:
  `CompletableFuture<Void> publicar(Video video)`. Corpo: obtém a chave do Pacote via
  `arquivoGateway.chaveDoPacote(video.id())`, envia `extracaoSender.enviarExtrairVideo(video.id(),
  video.chaveVideo(), chaveDestino)`, então `videoGateway.marcarComandoPublicado(video.id(),
  Instant.now())`.
- **`PublicarVideoFalhou`**, em `core/usecases/video/`. Construtor:
  `(NotificacaoSender, VideoGateway)`. Método público:
  `CompletableFuture<Void> publicar(Video video)`. Corpo: envia
  `notificacaoSender.enviarVideoFalhou(video.id(), video.dono(), video.nome(), video.motivo(),
  video.finalizadoEm())`, então `videoGateway.marcarFalhaPublicada(video.id(), Instant.now())`.
- **Sem interface nova.** Existe exatamente uma implementação real de cada sequência — não há
  infraestrutura alternativa a abstrair (ao contrário de `VideoGateway`/`ArquivoGateway`, que
  abstraem Postgres/MinIO para o framework poder trocar). Uma interface aqui seria um seam
  hipotético, não real.
- **Sem sufixo `UseCase`.** As duas classes nunca são chamadas por um controller, só por outros
  use cases — dar o sufixo herdaria, sem necessidade, a obrigação de forma que o
  `ArchitectureConstraintsTest` associa a esse sufixo (`executar(...)` retornando
  `CompletableFuture`), quando o papel real é de colaborador interno.
- **Construtores dos três chamadores mudam:**
  - `EnviarVideoUseCase`: perde a dependência direta de `ExtracaoSender`; ganha
    `PublicarExtrairVideo`. Mantém `ArquivoGateway` (agora só para `gravarVideo`) e
    `VideoGateway` (para `adicionar`).
  - `ProcessarExtracaoFalhouUseCase`: perde a dependência direta de `NotificacaoSender`; ganha
    `PublicarVideoFalhou`. Mantém `VideoGateway` (para `marcarFalha`).
  - `ReconciliarPublicacoesPendentesUseCase`: perde as dependências diretas de `ArquivoGateway`,
    `ExtracaoSender` e `NotificacaoSender`; ganha `PublicarExtrairVideo` e `PublicarVideoFalhou`.
    Mantém `VideoGateway` (para `buscarComandosPendentes`/`buscarFalhasPendentes`). O laço
    sequencial (`emSequencia`) e a razão documentada para ele (sessão reativa do Hibernate não
    tolera duas queries concorrentes no mesmo contexto) não mudam.
- **Wiring CDI**: os beans de `VideosConfiguration` (ou onde quer que os use cases sejam hoje
  construídos) precisam passar a instanciar `PublicarExtrairVideo` e `PublicarVideoFalhou` e
  injetá-los nos três chamadores, no lugar das dependências de mensageria removidas.
- **Nenhum ADR é reaberto.** O comportamento de publicação (ordem enviar-depois-marcar, guarda
  de unicidade via `UPDATE` condicional, folga contra crash, execução sequencial da varredura)
  é preservado exatamente como está — esta é uma extração de duplicação, não uma mudança de
  política.
- **Nenhum termo novo entra no `CONTEXT.md`.** `PublicarExtrairVideo` e `PublicarVideoFalhou`
  são nomes estruturais (nomeiam a mensagem que publicam, vocabulário que o `CONTEXT.md` já
  define), não conceitos de domínio novos.

## Testing Decisions

- **Testar comportamento externo, não a forma interna.** Os testes dos dois módulos novos
  devem verificar o efeito observável (a mensagem foi "enviada" no dublê do sender, a marca foi
  gravada no dublê do gateway com o `Instant` esperado) — não a ordem interna das chamadas nem
  detalhes de implementação que um refactor futuro possa mudar sem quebrar o contrato.
- **Seam**: unitário puro do `core`, sem Docker, sem Quarkus — o mesmo seam que os seis testes
  de use case de `core/usecases/video/` já usam hoje.
- **Prior art / dublês reaproveitados**: `GatewaysEmMemoria` (em
  `videos/src/test/java/br/com/fiapx/videos/core/usecases/video/GatewaysEmMemoria.java`) já
  fornece `Videos` (implementa `VideoGateway`), `ExtracaoEnvios` (implementa `ExtracaoSender`),
  `NotificacaoEnvios` (implementa `NotificacaoSender`) e `Arquivos` (implementa
  `ArquivoGateway`). Os testes novos de `PublicarExtrairVideo` e `PublicarVideoFalhou` reusam
  esses mesmos dublês, sem criar nenhum dublê novo.
- **Módulos com teste novo, dedicado**: `PublicarExtrairVideo` e `PublicarVideoFalhou` cada um
  ganha uma suíte própria, cobrindo a sequência enviar+marcar isolada da lógica dos três
  chamadores.
- **Testes existentes não mudam o que afirmam.** `EnviarVideoUseCaseTest`,
  `ProcessarExtracaoFalhouUseCaseTest` e `ReconciliarPublicacoesPendentesUseCaseTest` continuam
  construindo os mesmos dublês de `GatewaysEmMemoria` e asserindo nos mesmos campos
  (`videos.comandoPublicadoEm`, `videos.falhaPublicadaEm`, `extracao.idsEnviados`,
  `notificacao.idsEnviados`) — só passam a montar o SUT passando os dois módulos novos
  (construídos com os mesmos dublês) em vez de passar os dublês de mensageria diretamente ao
  use case.
- **Prior art para a forma da suíte nova**: seguir a estrutura de
  `ProcessarExtracaoFalhouUseCaseTest.java` (o mais próximo em tamanho e formato — poucos
  colaboradores, sem lógica condicional de laço) como modelo para as duas suítes novas.
- **Regressão de comportamento de ponta a ponta**: como o `ReconciliarPublicacoesPendentesUseCase`
  é exercitado contra Postgres/RabbitMQ reais em teste próprio já existente (verificado de ponta
  a ponta no ticket 017), essa cobertura de integração não precisa de mudança — só continuar
  passando após o refactor confirma que o wiring novo está correto.

## Out of Scope

- **Candidato 2 do relatório de arquitetura** (`VideoDTO` → `VideoViewModel` mapeado duas vezes
  em `VideoPresenterAdapter`/`VideosPaginadosPresenterAdapter`) — problema real, mas
  independente deste; pode virar spec própria.
- **Candidato 3 do relatório de arquitetura** (os três retornos diferentes de `VideoGateway`
  para as guardas de transição) — avaliado e descartado no grilling: é consequência direta e
  documentada do ADR 0002, não um defeito a corrigir.
- **Qualquer mudança na política de falhas, na máquina de estados ou na reconciliação por
  varredura** — ADRs 0001, 0002 e 0003 permanecem exatamente como estão; este spec só move
  código, não muda comportamento.
- **Mudança de contrato HTTP ou de mensageria** — nenhum dos cinco contratos em
  `docs/contratos/` muda.

## Further Notes

- Esta extração cumpre, em código, o que o [ADR 0003](../adr/0003-reconciliacao-por-varredura.md)
  já declarava como consequência pretendida ("um caminho de publicação, dois chamadores") —
  não é uma decisão nova, é a decisão existente sendo finalmente verdadeira na estrutura do
  código, não só na prosa do ADR.
- Origem: candidato 1 (`Strong`) do relatório de arquitetura de 2026-08-30, gerado pelo comando
  `/improve-codebase-architecture` a partir do hot spot de commits do serviço `videos`.
- Seams confirmados com o desenvolvedor antes de publicar este spec: nenhum seam novo, reuso
  total de `GatewaysEmMemoria`.
