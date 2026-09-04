# Override de canal por variável de ambiente quebra o boot do `extracao`

- id: 038
- label: wayfinder:bug
- status: aberto
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
