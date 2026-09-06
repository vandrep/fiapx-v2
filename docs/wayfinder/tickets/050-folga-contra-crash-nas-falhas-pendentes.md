# Folga contra crash também nas falhas pendentes da reconciliação

- id: 050
- label: wayfinder:bug
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...b4672ff`, convertido em ticket com aprovação
do usuário. A varredura de publicações pendentes aplica uma folga de tempo ao buscar
comandos pendentes e nenhuma folga ao buscar falhas pendentes. A folga existe para não
confundir publicação em voo com publicação perdida, que é a janela descrita no ADR 0003.
Sem ela, uma varredura que caia sobre um processamento de falha em voo republica o evento e
duplica a notificação. O ADR 0001 aceita entrega ao menos uma vez, então não há quebra de
contrato; a assimetria é que não tem explicação em documento nenhum.

## O que entregar

As duas metades da varredura tratam a janela entre gravar e publicar do mesmo jeito. Uma
varredura concorrente com um processamento de falha em voo deixa de gerar notificação
duplicada por essa causa — ou a assimetria fica registrada como decisão, com a razão.

## Condições de aceite

- [x] Decidir entre simetrizar a folga ou documentar a assimetria, e registrar a escolha
  junto do ADR 0003.
- [x] Se simetrizado: a busca de falhas pendentes passa a respeitar a mesma janela de
  proteção que a busca de comandos.
- [x] Verificar que uma varredura disparada durante uma publicação de falha em voo não
  republica o evento.
- [x] Verificar que uma falha realmente perdida continua sendo alcançada pela varredura,
  sem regressão do que o ADR 0003 garante.
- [x] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

**Simetrizado, não documentado como decisão.** A assimetria não tinha razão a registrar: a
janela que o ADR 0003 descreve — gravar num sistema, publicar noutro, sem transação comum — é
a mesma dos dois lados da varredura (`INSERT` → publish do `ExtrairVideo`, `UPDATE` para
`FALHOU` → publish do `VideoFalhou`), e o corte de um minuto já existia por essa razão na
metade do comando. Documentar a ausência do outro lado seria inventar uma justificativa para
o que foi descuido.

`ReconciliarPublicacoesPendentesUseCase` passou a calcular **um** instante de corte por
passada e a entregá-lo às duas buscas; `buscarFalhasPendentes` ganhou o parâmetro
`falhadosAntesDe`, e o adapter o aplica como `finalizadoEm < ?2`, com a ordenação passando de
`recebidoEm` para `finalizadoEm` — a ordem que a busca de comandos já usava, a do instante que
ela julga.

O instante julgado é o `finalizado_em`, que é onde `marcarFalha` grava. Ele vem do **evento**
`ExtracaoFalhou`, não da escrita da linha, então sob backlog de fila a folga efetiva encurta;
no pior caso ela vira a de antes deste ticket — zero —, e o pior caso continua sendo a
duplicata que o ADR 0001 aceita. Coluna nova só para o instante da escrita pagaria migração de
esquema por essa diferença, e não foi feita. Nada mudou no esquema: o índice parcial
`ix_video_falha_pendente` já era `(finalizado_em)`, então o predicado novo é servido por ele.

Verificação, nas duas camadas que o defeito atravessa:

- `ReconciliarPublicacoesPendentesUseCaseTest.umaFalhaPendenteMasRecenteNaoERepublicada` —
  falha gravada há 10 segundos não gera envio, não conta na passada e não recebe marca. É o
  gêmeo do teste que já existia para o comando, e falhava antes da mudança (`expected: <0> but
  was: <1>`).
- `ReconciliarPublicacoesPendentesUseCaseTest.umaFalhaPendenteEVelhaERepublicadaEMarcada` — a
  falha realmente perdida continua sendo alcançada; é o teste que já existia, agora com a
  linha envelhecida além da folga.
- `VideoDataSourceAdapterTest.aFalhaRecemGravadaFicaForaDaVarreduraEAJaVelhaEntra` — o
  predicado é HQL sobre `finalizadoEm`, camada que o dublê em memória não prova: a mesma linha
  é julgada por dois cortes contra Postgres de verdade. O instante é fixo, e não
  `Instant.now()`, porque `timestamptz` guarda microssegundos e um `now()` com nanos voltaria
  truncado para trás do próprio corte.

Registro: o ADR 0003 ganhou a consequência *Uma folga só, aplicada às duas metades da
varredura*, com a razão e o limite do `finalizado_em`; o comentário do índice em
`docker/postgres/init.sql` deixou de tratar a coluna como só ordenação.

A revisão de dois eixos sobre este trabalho levantou três coisas, e as três entraram:

- **`finalizado_em` nulo sumiria da varredura para sempre.** Comparação com `NULL` nunca é
  verdadeira, e o predicado antigo não olhava a coluna. Hoje só `marcarFalha` escreve `FALHOU`
  e sempre grava o instante, mas o esquema não obrigava; ganhou
  `ck_video_falhou_finalizado` no `init.sql`, para que um seed, um backfill ou uma rota futura
  quebrem alto em vez de em silêncio. O CHECK vale em `%prod` e no Compose — em teste o
  esquema vem do Hibernate, então ele não é exercido pela suíte.
- **A simetria não estava amarrada por teste.** Os dois testes de idade são independentes: um
  refactor que devolvesse folgas diferentes passaria verde nos dois. `asDuasMetadesDaVarreduraPedemOMesmoInstanteDeCorte`
  julga a propriedade que o ticket entrega — o dublê registra o corte que cada busca pediu, e
  o teste exige que sejam o mesmo.
- **O skew de relógio ficou registrado no ADR** junto do backlog: `finalizado_em` vem do
  relógio do `extracao` e o corte do `Instant.now()` do `videos`, então worker adiantado
  alonga a folga e atrasa o resgate, como backlog a encurta.

Duas observações da revisão foram registradas e **não** viraram código. A condição de aceite
fala em varredura concorrente com publicação em voo, e o que os testes encenam é *idade* — mas
o mecanismo **é** o corte por idade, e um teste de concorrência real aqui provaria o
escalonador, não a regra. E o dublê em memória levanta `NullPointerException` onde o SQL
apenas omitiria a linha: com o CHECK no esquema o estado é impossível, e um guarda de nulo
seria código para estado que não existe.

**Suíte verde a partir da raiz**, com Docker e `ffmpeg` de pé: 132 testes no `videos`, 268 no `extracao` e 24 no `notificacao`. O `init.sql` com o CHECK novo foi aplicado num Postgres descartável, para não descobrir erro de sintaxe só no boot do Compose. Como nos tickets anteriores, o
Keycloak da stack de demo ocupa a 8081 nesta máquina, então rodei com
`-Dquarkus.http.test-port=0`.
