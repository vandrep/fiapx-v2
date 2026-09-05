# O `iniciadaEm` que não é gravado em lugar nenhum

- id: 033
- label: wayfinder:grilling
- status: fechado
- assignee:
- bloqueado-por: 031

## Question

`VideoGateway.marcarIniciada(UUID id, Instant iniciadaEm)` recebe o instante em que a Extração
começou e **nenhum dos dois lados o usa**. O `VideoDataSourceAdapter` faz `SET estado` e nada
mais; `Video.marcaComoIniciada()` nem toma parâmetro; não há coluna `iniciada_em` na tabela
`video`. O valor viaja do consumidor até o SQL para ser descartado.

Um parâmetro que atravessa três camadas sem destino é uma promessa que o código faz e não
cumpre: quem lê a assinatura conclui que o instante é registrado.

## As duas saídas, e por que a escolha não é óbvia

**Remover o parâmetro** é honesto e barato, mas fecha uma porta. **Gravar o instante** custa
uma coluna — e o esquema está fechado nesta rodada: sem Flyway, mudar coluna é editar
`docker/postgres/init.sql`, e em `%prod` a estratégia é `validate`, então divergência derruba
o serviço no boot.

O que pesa a favor de gravar: `iniciada_em` é exatamente a coluna que faltaria para detectar
Vídeo preso em `PROCESSANDO`. Hoje não existe varredura para esse caso, e o
[ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) recusou por escrito a varredura por
idade — para `RECEBIDO` a recusa está certa, porque backlog legítimo de fila é
indistinguível de Vídeo perdido. Para `PROCESSANDO` a distinção seria idade **desde o início da
Extração**, que é o dado descartado. O parâmetro morto e a varredura ausente são o mesmo buraco
visto de dois ângulos.

## Por que não agora

O caminho até `PROCESSANDO` preso passa pelo [029](029-terminal-na-dlq-do-extracao.md), que é
configuração. Abrir o esquema para um cenário que a config elimina é pagar caro por precaução.
Feche o 029, meça, e volte aqui com um número — ou remova o parâmetro.

## Condição de aceite

Uma das duas, com o motivo escrito: parâmetro removido das três camadas, ou coluna criada e
varredura de `PROCESSANDO` justificada por medição.

## Decisão

O parâmetro foi removido do caminho interno do `videos`: controller, command do use case,
gateway e adapter agora carregam apenas o identificador necessário para aplicar a transição.
O campo `iniciadaEm` permanece no record de mensageria dos dois serviços, porque removê-lo
seria uma mudança incompatível no contrato; o consumidor tolerant reader o desserializa e o
descarta na borda.

Não foi criada coluna nem varredura. O ticket 029 fechou a perda silenciosa que motivava essa
precaução e tornou uma falha residual visível no Estacionamento, mas não trouxe medição que
justifique reabrir o esquema. Sem esse número, persistir o instante criaria estado e um
processo de recuperação especulativos, contrariando a condição de aceite deste ticket.
