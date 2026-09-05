# Override de canal por variável de ambiente quebra o boot do `extracao`

- id: 038
- label: wayfinder:bug
- status: resolvido
- assignee:
- bloqueado-por:

## O que acontece

Basta a **presença** das duas variáveis que o `docker-compose.carga.yml` declarava para o
`extracao` — mesmo com os valores default, idênticos aos do `application.properties` — para o
serviço não subir:

```
docker run ... \
  -e MP_MESSAGING_OUTGOING_EXTRACAO_FALHOU_EXCHANGE_NAME=fiapx.eventos \
  -e MP_MESSAGING_OUTGOING_EXTRACAO_FALHOU_EXCHANGE_DECLARE=true \
  ghcr.io/vandrep/fiapx-extracao:latest

SRMSG00071: Invalid channel configuration -
  the `connector` attribute must be set for channel `extracao`
```

A causa é o traço do nome do canal. `extracao-falhou` vira `EXTRACAO_FALHOU` na variável de
ambiente, e o caminho de volta é ambíguo: a **busca por nome** funciona (o SmallRye Config
sabe procurar `MP_MESSAGING_OUTGOING_EXTRACAO_FALHOU_EXCHANGE_NAME` para
`mp.messaging.outgoing.extracao-falhou.exchange.name`), mas a **enumeração** de nomes de
propriedade devolve `mp.messaging.outgoing.extracao.falhou.exchange.name`, e é dela que o
`ConfiguredChannelFactory` deduz quais canais existem. Ele deduz um canal chamado `extracao`,
que ninguém declarou e que portanto não tem `connector` — e o boot morre em
`MediatorManager.start`.

## Por que nunca apareceu

Porque o modo que precisa dessas variáveis nunca rodou contra o Compose de verdade. O
[029](029-terminal-na-dlq-do-extracao.md) registrou isso no `map.md` como pendência ("o
sandbox usado não roteia porta publicada de container"), e as variáveis com default no mapa
faziam o defeito atingir **todos os quatro modos**, não só o `mata-publicacao` — nenhum modo do
`conservacao.sh` conseguia subir o stack.

Achado no [035](035-drenar-extracao-antes-do-sigterm.md), que precisava do harness de carga de
pé para medir o dreno.

## O que já foi feito, e o que falta

O 035 fez o mínimo para se desbloquear: as duas variáveis passaram para a **forma de lista** no
`docker-compose.carga.yml`, então elas só existem no container quando o harness as exporta.
Os modos `limpo`, `mata-extracao`, `redeploy-extracao` e `mata-videos` voltaram a subir.

O `mata-publicacao` **continua quebrado**, agora com a causa conhecida: ele precisa das duas
variáveis, e a presença delas é o defeito. Consertá-lo exige escolher um caminho, e nenhum é
óbvio:

1. **Renomear o canal** para algo sem traço (`extracaofalhou`). Conserta a raiz e vale para
   qualquer override futuro, mas nome de canal SmallRye aparece em quatro lugares do
   `application.properties` e a legibilidade piora.
2. **Injetar a config sem variável de ambiente** — um `application.properties` montado por
   volume, ou `-D` no comando. O `ENTRYPOINT` é `exec` form (`["java", "-jar", ...]`, ticket
   006/015), então `command:` vira argumento de aplicação, não propriedade de sistema:
   exigiria sobrescrever o `entrypoint` só no overlay.
3. **Quebrar o canal por outro meio** — apontar a `fiapx.eventos` para um vhost sem ela, por
   exemplo. Mede a mesma coisa sem tocar em nome de canal, mas é mais indireto de ler.

## Condição de aceite

`scripts/carga/conservacao.sh mata-publicacao` sobe o stack e chega ao veredito, com os três
critérios que ele imprime antes de rodar julgados de verdade — pela primeira vez.

## Solução

O canal conserva o nome `extracao-falhou`. As duas propriedades de exchange passam a
resolver expressões com variáveis próprias `FIAPX_EXTRACAO_FALHOU_EXCHANGE_NAME` e
`FIAPX_EXTRACAO_FALHOU_EXCHANGE_DECLARE`, com defaults `fiapx.eventos` e `true`.
O overlay e o harness usam essas variáveis: a enumeração não acrescenta propriedades sob
`mp.messaging`, então não inventa um canal `extracao`. Isso dispensa renomear o canal ou
sobrescrever o entrypoint da imagem.

Overrides diretos `MP_MESSAGING_OUTGOING_EXTRACAO_FALHOU_*` continuam sujeitos à ambiguidade
do SmallRye; o caminho suportado para estas duas propriedades é o par `FIAPX_*` acima.

## Validação

- Reprodução antes da correção: `docker compose run --rm --no-deps` com as duas variáveis
  antigas e valores default encerrou com código 1 e `SRMSG00071`, canal `extracao` sem
  `connector` (`/tmp/038-antes.log`).
- Build dos serviços: `./mvnw package -DskipTests` no devcontainer e reconstrução local da
  imagem `extracao`.
- Suíte completa na raiz: `./mvnw test -Dquarkus.http.test-port=0` no devcontainer;
  392 testes (105 videos, 263 extracao, 24 notificacao), zero falhas/erros/skips.
- `scripts/smoke.sh` aprovado após restaurar o Compose normal: processamento, download,
  falha com notificação e isolamento por dono. Log em `/tmp/038-smoke.log`.
- A regressão é exercitada pelo próprio harness no Compose: um profile com overrides
  diretos de propriedades não reproduz a enumeração de variáveis de ambiente deste bug.
- Aceite executado com `COMPOSE_PROJECT_NAME=fiapx-v2 FIAPX_ROTULO=038-mata-publicacao
  scripts/carga/conservacao.sh mata-publicacao`: quatro réplicas do extracao, seis containers
  saudáveis, três envios. O harness chegou ao veredito e encerrou com código 1:
  - Critério 1 aprovado: três HTTP 202, zero recusas.
  - Critério 2 aprovado: três de três Vídeos em `PROCESSANDO`, nenhum terminal ou ausente.
  - Critério 3 reprovado: zero mensagens novas no estacionamento em 241s, base zero,
    limite original de 240s mantido.
  Log em `/tmp/038-carga.log`; censo e IDs em `scripts/carga/saida/038-mata-publicacao/`.

O aceite deste ticket é chegar ao veredito com os três critérios julgados, não obter três
aprovações. O boot está corrigido; a garantia de estacionamento do 029 **não foi confirmada**
no prazo do harness e permanece pendente de diagnóstico. A medição não distingue atraso,
circulação ou perda; não se atribui causa sem investigar. Logs do extracao foram capturados
antes de restaurar o stack em `/tmp/038-extracao-final.log`.
