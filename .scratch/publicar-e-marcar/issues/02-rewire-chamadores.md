# 02: Rewire dos três chamadores para usar os publicadores extraídos

**What to build:** `EnviarVideoUseCase`, `ProcessarExtracaoFalhouUseCase` e
`ReconciliarPublicacoesPendentesUseCase` passam a delegar a `PublicarExtrairVideo` e/ou
`PublicarVideoFalhou` (do ticket 01) em vez de reimplementar a sequência "enviar → marcar".
`EnviarVideoUseCase` perde a dependência direta de `ExtracaoSender` (mantém `ArquivoGateway`
só para `gravarVideo`, e `VideoGateway` para `adicionar`) e ganha `PublicarExtrairVideo`.
`ProcessarExtracaoFalhouUseCase` perde a dependência direta de `NotificacaoSender` (mantém
`VideoGateway` para `marcarFalha`) e ganha `PublicarVideoFalhou`.
`ReconciliarPublicacoesPendentesUseCase` perde as dependências diretas de `ArquivoGateway`,
`ExtracaoSender` e `NotificacaoSender` (mantém `VideoGateway` para as buscas de pendentes) e
ganha os dois. O wiring CDI (onde os use cases são hoje construídos) passa a instanciar os dois
publicadores e injetá-los. Nenhum comportamento observável muda: ordem enviar-depois-marcar,
guarda de unicidade da transição, folga contra crash, e execução sequencial da varredura
continuam idênticas — é remoção de duplicação, não mudança de política. Esse é o ticket onde o
ADR 0003 ("um caminho de publicação, dois chamadores") passa a ser verdade no código, não só na
prosa do ADR.

**Blocked by:** 01: Extrair PublicarExtrairVideo e PublicarVideoFalhou

**Status:** ready-for-agent

- [ ] `EnviarVideoUseCase` delega a `PublicarExtrairVideo.publicar(video)` em vez de chamar
      `ExtracaoSender`/`VideoGateway.marcarComandoPublicado` diretamente
- [ ] `ProcessarExtracaoFalhouUseCase` delega a `PublicarVideoFalhou.publicar(video)` em vez de
      chamar `NotificacaoSender`/`VideoGateway.marcarFalhaPublicada` diretamente
- [ ] `ReconciliarPublicacoesPendentesUseCase` delega aos dois publicadores em vez de
      reimplementar as sequências; o laço sequencial (`emSequencia`) e sua justificativa
      (sessão reativa do Hibernate não tolera queries concorrentes no mesmo contexto) não mudam
- [ ] Wiring CDI atualizado: os três use cases recebem os publicadores construídos com as
      dependências reais (Rabbit/MinIO/Postgres via os adapters existentes), não mais as
      dependências de mensageria diretamente
- [ ] `EnviarVideoUseCaseTest`, `ProcessarExtracaoFalhouUseCaseTest` e
      `ReconciliarPublicacoesPendentesUseCaseTest` continuam asserindo exatamente o mesmo
      comportamento (`videos.comandoPublicadoEm`, `videos.falhaPublicadaEm`,
      `extracao.idsEnviados`, `notificacao.idsEnviados`), só passando a montar o SUT com os
      publicadores do ticket 01 (construídos com os mesmos dublês de `GatewaysEmMemoria`) em vez
      de com os dublês de mensageria diretamente
- [ ] Nenhum teste novo de comportamento é necessário além dos três acima — a cobertura de
      integração existente (ticket 017, contra Postgres/RabbitMQ reais) continua passando sem
      mudança, confirmando que o wiring novo está correto
- [ ] A suíte completa do serviço `videos` está verde de ponta a ponta
- [ ] Nenhum ADR é alterado; nenhum contrato em `docs/contratos/` muda

