# 01: Extrair PublicarExtrairVideo e PublicarVideoFalhou

**What to build:** dois módulos novos e concretos em `core/usecases/video/` (sem sufixo
`UseCase`, sem interface): `PublicarExtrairVideo` — construtor `(ArquivoGateway, ExtracaoSender,
VideoGateway)`, método `CompletableFuture<Void> publicar(Video video)` que obtém a chave do
Pacote, envia `ExtrairVideo` e marca `comando_publicado_em`; e `PublicarVideoFalhou` —
construtor `(NotificacaoSender, VideoGateway)`, método `CompletableFuture<Void> publicar(Video
video)` que envia `VideoFalhou` e marca `falha_publicada_em`. Nenhum código existente é tocado:
`EnviarVideoUseCase`, `ProcessarExtracaoFalhouUseCase` e `ReconciliarPublicacoesPendentesUseCase`
continuam exatamente como estão, chamando diretamente `ExtracaoSender`/`NotificacaoSender` como
hoje. Este ticket só adiciona os dois módulos e seus testes.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `PublicarExtrairVideo` existe em `core/usecases/video/`, sem sufixo `UseCase`, com o
      construtor e o método `publicar(Video)` descritos acima
- [ ] `PublicarVideoFalhou` existe em `core/usecases/video/`, sem sufixo `UseCase`, com o
      construtor e o método `publicar(Video)` descritos acima
- [ ] Teste unitário de `PublicarExtrairVideo`, reusando os dublês existentes de
      `GatewaysEmMemoria` (`Arquivos`, `ExtracaoEnvios`, `Videos`) — sem dublê novo — cobrindo:
      o comando é enviado com a chave de vídeo e a chave de destino do Pacote corretas, e a
      marca `comandoPublicadoEm` é gravada só depois do envio
- [ ] Teste unitário de `PublicarVideoFalhou`, reusando os dublês existentes de
      `GatewaysEmMemoria` (`NotificacaoEnvios`, `Videos`) — sem dublê novo — cobrindo: a
      notificação é enviada com dono, nome do arquivo, motivo e `finalizadoEm` corretos, e a
      marca `falhaPublicadaEm` é gravada só depois do envio
- [ ] `EnviarVideoUseCase`, `ProcessarExtracaoFalhouUseCase` e
      `ReconciliarPublicacoesPendentesUseCase` permanecem inalterados; a suíte completa do
      serviço `videos` continua verde
- [ ] Nenhum termo novo entra em `CONTEXT.md`; nenhum ADR é alterado

