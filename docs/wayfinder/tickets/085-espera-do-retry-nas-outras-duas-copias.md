# A espera do retry ficou configurável só numa das três cópias

- id: 085
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Medido durante o [ticket 080](080-custo-de-teste-do-blip-sem-substituto.md), registrado lá como
fora de escopo e convertido em ticket com aprovação do mantenedor.

## O problema

O 080 tornou a espera entre repetições configurável por perfil no `ArquivoMinioClient` do
`videos`, e o cenário do blip caiu de 26,46 s para 6,17 s. Ele mediu, de passagem, que as outras
duas cópias de `comRepeticao` pagam o mesmo custo pela mesma aritmética:

| Teste | Cópia | Tempo |
|---|---|---|
| `RepeticaoNoMinioTest` | `extracao/.../ArquivoMinioClient` | **14,30 s** |
| `RepeticaoNoSmtpTest` | `notificacao/.../MailerEmailClient` | **10,19 s** |

Nas duas, `ESPERA_ENTRE_REPETICOES` continua `private static final Duration.ofSeconds(2)`, sem
perfil que a altere — exatamente o estado de que o `videos` saiu.

`comRepeticao` é uma das cinco famílias do `AGENTS.md` § *As cópias deliberadas entre serviços*,
que manda inspecionar todas as cópias ao mudar a parte comum e aplicar "somente o que preserva o
mesmo contrato". O 080 **inspecionou e mediu** — essa metade está cumprida —, mas não aplicou, e
com isso a divergência foi *introduzida*: hoje uma cópia lê a espera de configuração e duas a têm
fixa. O mesmo parágrafo diz "não as force a convergir", o que dá cobertura à decisão de esperar;
por isso isto é ticket, e não defeito.

## O que entregar

Uma das duas, decidida e escrita:

- A espera vira configurável nas outras duas cópias, no mesmo desenho do `videos`: chave no
  namespace `fiapx.` do próprio serviço, **default no código** e não no `.properties`, e override
  só no perfil de teste. As três cópias voltam a ter a mesma forma.
- Ou as duas ficam como estão, e o motivo vai escrito — no `AGENTS.md` § *As cópias deliberadas
  entre serviços*, que é onde a divergência legítima entre cópias se registra.

Se for a primeira, vale notar que o piso é **1 ms e não zero**: o Mutiny recusa backoff zero com
`IllegalArgumentException` na subscrição, achado do 080 que custou uma medição.

## Critérios de aceite

- [ ] O custo dos dois cenários está medido antes e depois, com número
- [ ] A escolha entre ajustar e manter está registrada, com o motivo
- [ ] Nenhuma chave de tolerância a falhas por interceptor entra em `.properties` nenhum — a
      sétima regra do teste arquitetural (`toleranciaAFalhasNaoPodeSerConfigurada`) segue verde
      nos três
- [ ] A contagem de repetições continua constante nas três cópias: ela é a política do
      [ADR 0001](../../adr/0001-politica-de-falhas.md), e só a espera é preço
- [ ] As três cópias de `comRepeticao` têm a mesma forma, ou a diferença está escrita no
      `AGENTS.md`
- [ ] `videos` e `notificacao` verdes; `extracao` sem regressão além das falhas por ausência de
      `ffmpeg`/`ffprobe` no host, que o 080 registrou
