# Implementação do serviço extracao

- id: 015
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 007, 011

## Question

Worker sem estado. O ffmpeg já está decidido (ticket 006: processo externo, `-xerror`, ZIP
`STORED`, classificação por exit code) e o contrato de mensagens fechou (ticket 007). Falta
o teto de frames/duração que o ticket 011 decide — é ele que este serviço tem de impor.

Implementar, test-first, conforme
[`docs/contratos/mensagens.md`](../../contratos/mensagens.md) e o `AGENTS.md` do template:

- Consumidor de `extracao.extrair` e o pipeline: baixar o Vídeo do MinIO pela `chaveVideo`,
  rodar ffmpeg, empacotar em ZIP `STORED`, subir na `chaveDestinoPacote`. Streaming ponta a
  ponta (ticket 005) — nada de `toBytes`.
- Publicação de `ExtracaoIniciada`, `ExtracaoConcluida` e `ExtracaoFalhou`.
- Classificação de falha a partir do exit code **e** do stderr (o exit 8 colide), mapeada
  para `codigoMotivo`. Conferência da contagem de frames contra a duração do `ffprobe`.
- Os dois caminhos de falha: permanente publica direto e dá ack; transitória dá `nack` e
  esgota o `x-delivery-limit`.
- **Consumidor da própria DLQ** `extracao.extrair.dlq`, publicando `TENTATIVAS_ESGOTADAS` —
  sem ele o Vídeo trava em `PROCESSANDO`.
- `@Retry` com backoff de segundos nos adapters de I/O (MinIO), conforme ADR 0001.
- `max-outstanding-messages=1`.
- Imagem `eclipse-temurin:21-jre-alpine` + `apk add --no-cache ffmpeg`, diretório temporário
  de trabalho e sua limpeza.
- Health check e a regra nova no `ArchitectureConstraintsTest`.
