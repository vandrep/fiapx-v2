# Recusa por capacidade antes de receber o corpo

- id: 108
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial d161987)
- bloqueado-por: 104
- prioridade: P2

## Origem

Sessão de grilling com o mantenedor em 2026-09-13, sobre `develop @ 0d46527`, série 103–109
sobre *Vídeo perdido* ([`CONTEXT.md`](../../../CONTEXT.md)). A pergunta foi se o sistema
consegue impedir novos pedidos quando não há recurso para todos.

## O problema

Não há recusa por sobrecarga em lugar nenhum: sem `429` ou `503`, sem teto de uploads
simultâneos, sem guarda de disco. A extração não satura, porque vira backlog na fila quorum, e
isso é o amortecedor de pico que o enunciado pede. Quem satura é a borda: o Vert.x grava o
corpo inteiro, até 200 MB, no volume `fiapx-uploads` **antes** de o resource rodar, e o disco
cheio vira `500`.

## Decisão

- Recusar pelo **recurso local da borda**, não pelo backlog da fila. O backlog só alimenta
  alerta; recusar por ele trocaria "não perder" por "não aceitar" justamente no pico.
- A decisão acontece **antes de ler o corpo**, num filtro de rota: uploads em andamento na
  réplica acima de um teto, ou `Content-Length` declarado maior que o espaço livre do volume de
  uploads, resultam em `503` com `Retry-After`. Upload sem `Content-Length` é contido pelo teto
  de concorrência.
- Falha ao gravar no MinIO, a primeira escrita, quando nada mais foi gravado, também vira
  `503`, não `500`.
- Recusa explícita não é Vídeo perdido: o sistema não assumiu o Vídeo.

Fora de escopo, registrado no [109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md):
cota por Dono e fila justa.

## Critérios de aceite

- [x] Teste: requisição acima do teto recebe `503` + `Retry-After` sem que o corpo chegue ao
      volume de uploads.
- [x] Teste: `Content-Length` maior que o espaço livre recebe `503`.
- [x] Teto de concorrência derivado do tamanho do volume e do limite de 200 MB, e calibrado com
      `scripts/carga/borda.sh`; comando, configuração e resultado registrados.
- [x] `503` documentado em `docs/contratos/http-videos.md`, em `problem+json` se o ponto de
      recusa permitir; se não permitir, registrar a inconsistência como a do `413`.
- [x] Linha em "Decisões até aqui" no mapa.
- [x] `./mvnw test` e `scripts/smoke.sh` verdes.

## Resolução

Implementado em 2026-09-14 sobre `develop @ d161987`.

**Filtro.** `RecusaPorCapacidade` (`framework/web`) registra uma rota Vert.x em `POST /videos` na
ordem `ROUTE_ORDER_BODY_HANDLER - 1`. Uma coisa que o ticket não sabia: no Quarkus REST quem lê o
multipart é o `FormBodyHandler`, dentro da rota do JAX-RS, e o body handler global do Vert.x não
está instalado. A ordem escolhida fica à frente dos dois. A rota decide só com os cabeçalhos, e a
conta é de `CapacidadeDoEnvio`:

- recusa quando os envios em andamento na réplica chegam ao teto;
- recusa quando o `Content-Length` passa do espaço livre do volume **menos o que os envios em
  andamento ainda vão gravar**. Isso vai além da letra do ticket: sem a reserva, dois envios
  passam cada um sozinho pela conta e enchem o volume juntos. Envio sem `Content-Length` reserva
  os 200 MB;
- `Content-Length` acima de 200 MB passa direto, e o `413` continua dele.

A vaga volta pelo `addEndHandler`, que cobre tanto o fim da resposta quanto a conexão fechada.

**Resposta.** `503`, `Retry-After: 5`, `Connection: close` e problem+json com título
`Capacidade esgotada`. O ponto de recusa permite problem+json, então não há inconsistência como a
do `413`. Os 5 s são sugestão, não medida.

**MinIO.** `ArquivoMinioAdapter.gravarVideo` traduz a falha, já depois das repetições, em
`ArmazenamentoIndisponivelException` (`core`). O mapper responde `503` com `Retry-After` e título
`Armazenamento indisponivel`. O `INSERT` continua `500`, e o download também.

**Teto.** Sem configuração, vale `tamanho do volume / max-body-size`, calculado no boot e escrito no
log. `fiapx.borda.teto-de-envios-simultaneos` o substitui; o perfil de teste usa 2, e o overlay de
carga passa `FIAPX_BORDA_TETO_DE_ENVIOS_SIMULTANEOS`.

### Calibração

Com `scripts/carga/borda.sh escala 1`, imagem do `videos` construída localmente (a anterior ficou
como `ghcr.io/vandrep/fiapx-videos:antes-108`), fixture `controle-3s.mp4`, 400 envios e 2 réplicas
de `extracao`. O volume está no disco do host, de 460 G, e o teto derivado deu **2354**.

| Rodada | Comando | Resultado |
|---|---|---|
| A | `FIAPX_ROTULO=ticket108-derivado borda.sh escala 1` (400 VUs) | 263 `202`, 137 falhas de **conexão** (status 0, broken pipe e reset). O nginx do proxy de carga registrou `512 worker_connections are not enough`, e o `videos` não registrou recusa nenhuma. Os 263 chegaram a terminal, sem presos e com os frames certos |
| A2 | `FIAPX_VUS=200 FIAPX_ROTULO=ticket108-derivado-vus200 borda.sh escala 1` | **400/400 `202`, zero recusas**, os seis critérios verdes; mediana do `202` 6,2 s, p95 11,6 s |
| B | `FIAPX_VUS=200 FIAPX_BORDA_TETO_DE_ENVIOS_SIMULTANEOS=8 FIAPX_ROTULO=ticket108-teto8-vus200 borda.sh escala 1` | 8 `202` e **392 `503`**, com as mesmas 392 linhas `Envio recusado por capacidade` no log do `videos`; os 8 aceitos chegaram a `CONCLUIDO`, sem presos e sem erro de conexão no proxy; mediana 841 ms |

O que a calibração prova, e o que não prova:

- A rajada da demo não chega perto do teto derivado, então a derivação não recusa trabalho
  legítimo nesta máquina.
- Com o teto baixo, a recusa sai rápida e limpa, e não custa nada aos Vídeos aceitos.
- **O valor 2354 não foi validado como limite**: nada foi medido acima de ~200 envios simultâneos.
  No Compose o volume nomeado não tem tamanho próprio, e o teto derivado é do disco do host, então
  depende da máquina. Quem protege o volume na prática é a conta do espaço livre com reserva.
- A rodada A mostra que, com 400 VUs, o instrumento satura antes do `videos`. O `nginx.conf` do
  proxy não declara `worker_connections`, e fica com os 512 do padrão. Não foi corrigido aqui,
  porque é o instrumento do 028, e as rodadas úteis daquele ticket também saíram com `FIAPX_VUS`
  reduzido.

### Limites registrados

- **A conta é por réplica.** Réplicas sobre o mesmo volume, como no overlay de carga, não enxergam
  a reserva umas das outras.
- **A recusa vem antes da autenticação.** Sem vaga, um envio sem token recebe `503`, e não `401`.
  O ticket não decidiu isso; é consequência de decidir antes do corpo, e o teste a fixa.
- Com N réplicas, o `proxy_next_upstream http_503` do proxy de carga conta o `503` como falha da
  réplica (`max_fails=1`) e a tira de circulação por 2 s. Isso não foi exercitado, porque as
  rodadas foram com N=1.

### Validação

- `CapacidadeDoEnvioTest`: a conta, sem Vert.x.
- `RecusaPorCapacidadeTest`: sockets crus seguram envios pela metade. A recusa chega **com o corpo
  pela metade**, e o número de arquivos no volume não muda. O teste também cobre o envio sem token,
  o `Content-Length` maior que o espaço livre (com o volume dublado), a vaga que volta depois de
  envio terminado ou conexão fechada, e o `413` preservado. Com a recusa trocada por uma que espera
  o corpo, o teste reprova por timeout de leitura, e isso foi conferido.
- `EnvioResisteABlipDoArmazenamentoTest`: o armazenamento fora no envio passou de `500` para `503`.
  Reprovou antes da mudança.
- `./mvnw test` na raiz: 512 testes verdes (193 `videos`, 290 `extracao`, 29 `notificacao`),
  antes e depois das correções da revisão.
- `scripts/smoke.sh --derruba` verde, com a imagem local construída antes da revisão. As correções
  da revisão são de forma (constante, nomes, try/catch) e não mudam status, cabeçalho nem corpo.

### Revisão

`/code-review` em dois eixos. Corrigido depois dela: a espera sugerida e o `detail` dos dois `503`
passaram para `ProblemDetail`, sem repetição entre a rota e o mapper; o try/catch duplicado saiu de
`VolumeDeUploads`; o status virou nomeado; os dois tetos ganharam nomes com unidade. Os limites
acima e a leitura honesta da calibração também vieram da revisão. Ficou para o 109 e o 111 o que já
era deles: cota por Dono, fila justa e a narração do pico em `docs/arquitetura.md`.
