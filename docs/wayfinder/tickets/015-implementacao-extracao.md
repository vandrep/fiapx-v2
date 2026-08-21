# Implementação do serviço extracao

- id: 015
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 007, 011

## Question

Worker sem estado. O ffmpeg já está decidido (ticket 006: processo externo, `-xerror`, ZIP
`STORED`, classificação por exit code) e o contrato de mensagens fechou (ticket 007). Falta
o teto de duração, que o ticket 011 já decidiu — é este serviço que tem de impô-lo.

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
- **Teto de duração de 20 minutos** (ticket 011), lido do `ffprobe` que já roda para
  conferir a contagem de frames. Acima dele, falha **permanente**: publica
  `DURACAO_EXCEDIDA` e dá ack, sem gastar as três entregas.
- Imagem `eclipse-temurin:21-jre-alpine` + `apk add --no-cache ffmpeg`. Scratch em
  `/var/fiapx/extracao/{idVideo}` sobre o volume `fiapx-extracao-scratch`, orçado em 4 GB
  (ticket 011): diretório **apagado-e-recriado** no início de cada tentativa, `finally` por
  mensagem e **varredura no boot** — o worker morre no meio por desenho, então limpeza no
  fim do processo não basta. Frames em disco é o caminho orçado; `image2pipe` alimentando o
  `ZipOutputStream` corta o pico pela metade e fica como otimização **opcional** desta
  implementação, com a ressalva de que fatiar PNGs concatenados briga com a classificação
  por exit code e com a contagem de frames.
- Chaves de objeto chegam prontas nas mensagens — este serviço **não** conhece a convenção
  do ticket 011 nem os nomes dos buckets.
- Health check e a regra nova no `ArchitectureConstraintsTest`.
