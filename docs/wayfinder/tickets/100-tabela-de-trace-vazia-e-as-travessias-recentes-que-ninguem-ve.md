# A tabela de trace nasce vazia, e as travessias recentes que ninguém vê

- id: 100
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-12)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-12, pedido assim: *"no painel FIAP X — a infraestrutura está saudável?
preencha a lista de traces com os ids mais recentes"*.

## Conflito com o registro, primeiro

Não há conflito, e vale dizer por que a pergunta cabe: o quarto pedido seguido sobre painel
vazio é o **primeiro** que não é sobre painel de erro. O mapa manda olhar o ADR 0004
§ *Os três dashboards de fábrica* antes de um terceiro pedido igual, e o `Error Rate`/`Error %`
vazios de lá continuam não sendo defeito. Este é outro painel e outra causa: a tabela de trace
do painel curado não está vazia por falta de dado — está vazia porque a query **exige** um
`idVideo` que ninguém digitou ainda.

O que o registro fixa sobre ela, e este ticket preserva inteiro:

- **`idVideo` é a chave que o humano digita; `trace_id` não sai do sistema** (ADR 0004 § homônimo).
  O textbox continua existindo e continua filtrando.
- **A busca precisa da âncora em `resource.service.name = "fiapx-extracao"`**, porque buscar só
  por `idVideo` casa dezenas de traces de uma linha — o atributo marca também o span de cada GET
  de acompanhamento (ADR 0004, ticket 092, passo 10 do `smoke.sh`).
- **Dois spansets ligados por `&&`**, e não duas condições num, porque o span do `ffmpeg` não
  carrega `idVideo`.

O que este ticket **reverte** é menor, e é do 092: aquele ticket viu o mesmo defeito — *"com o
textbox vazio na primeira abertura, a tabela vazia lia como painel quebrado em vez de campo por
preencher"* — e respondeu no **título** (*Trace da travessia — preencha o idVideo no topo*). A
mitigação por título não sobrevive ao pedido acima: quem abre o painel na demo continua diante de
uma tabela sem linha.

## O que foi medido

Compose de pé, Tempo 3.0.3, janela de 2 h, pela API do Grafana (`/api/datasources/proxy/uid/tempo`):

| Query | Traces |
|---|---|
| `{ .idVideo = "" } && { resource.service.name = "fiapx-extracao" }` — o estado de hoje, textbox vazio | **0** |
| `{ .idVideo =~ ".*.*" } && { resource.service.name = "fiapx-extracao" }` — a mesma query com regex | **6** |
| `{ .idVideo =~ ".*.*" }` — sem a âncora | **17** |
| `{ .idVideo =~ ".*<uuid>.*" } && { ... }` — textbox preenchido | **1**, o mesmo que a forma com `=` |

As duas linhas do meio são o ticket inteiro: `=` com string vazia casa zero, `=~` com regex vazia
casa tudo que **tem** o atributo, e a âncora continua sendo o que separa 6 travessias de 17
traces — a medição do ADR 0004 se confirma no estado novo, com número desta sessão.

## Os caminhos, e o preço de cada um

1. **Dois targets no mesmo painel** — um com o filtro exato, outro com a lista recente. O Grafana
   mostra duas séries de quadros numa tabela só, com seletor; e o passo 12 do `smoke.sh` julgaria
   os dois. Preço: duas verdades sobre a mesma pergunta no mesmo painel, que é o que a decisão de
   forma do ADR 0004 evita nas expressões de fila.
2. **Um painel novo, só de travessias recentes.** Preço: o painel curado é **um** de propósito, e
   a recusa que o 092 reverteu em parte é de **suíte** de painel; duas tabelas de trace lado a
   lado, uma sempre vazia, é a suíte começando.
3. **A mesma query servindo os dois estados, por regex.** `.idVideo =~ ".*$idVideo.*"`: textbox
   vazio vira `".*.*"` e lista as travessias recentes, mais novas primeiro; textbox preenchido
   filtra. Preço: a condição passa a significar "contém" em vez de "igual a" — com um UUID inteiro
   digitado dá no mesmo (medido acima), com um pedaço colado dá match parcial.

**Escolhido o 3.** O preço é o que o pedido compra: um estado a menos no painel, uma query só, e
`limit: 20` já entrega "as mais recentes" porque o Tempo devolve por tempo decrescente (medido).
O match parcial é útil mais vezes do que atrapalha — colar os oito primeiros dígitos do id acha a
travessia.

## Critérios de aceite

- [x] Com o textbox vazio, a tabela lista as travessias recentes, a mais nova primeiro
- [x] Com o textbox preenchido, a tabela filtra pelo `idVideo` como antes
- [x] A âncora e os dois spansets continuam onde estão, e o `description` continua dizendo por quê
- [x] O título deixa de mandar preencher o que já não é obrigatório
- [x] O passo 12 do `smoke.sh` cobre o estado de textbox **vazio**, que é o que a demo abre
- [x] `scripts/smoke.sh` verde

## Resolução

Quatro arquivos, nenhum código Java, nenhum JSON de fábrica tocado.

**`docker/observabilidade/painel-infraestrutura.json`** — a query do painel de trace passou de
`{ .idVideo = "$idVideo" }` para `{ .idVideo =~ ".*$idVideo.*" }`, ganhou
`| select(.idVideo)` e manteve o segundo spanset e o `limit: 20`. O título deixou de mandar
preencher (*Trace da travessia — as mais recentes, ou filtre por idVideo no topo*), e o
`description` do painel e o da variável passaram a descrever os dois estados — é lá que o porquê
mora, porque JSON não carrega comentário.

O `select(.idVideo)` não muda quais traces casam nem a ordem: com `tableType: "traces"` as
colunas da tabela são fixas (trace id, início, serviço, nome, duração), e o que ele acrescenta é
a coluna `idVideo` na **sub-tabela de spans** de cada linha expandida — medido pelo
`/api/ds/query` do Grafana, que é quem monta os quadros que a tela desenha. Sem ele, quem expande
uma linha da lista recente não tem como ligá-la a um Vídeo, e `idVideo` é justamente a chave que
o humano tem em mãos (ADR 0004).

**`scripts/smoke.sh`** — o passo 12 ganhou uma segunda passagem, depois do laço: para cada target
do datasource `tempo`, ele resolve `$idVideo` para **vazio** e reprova se a busca não listar
travessia nenhuma. O laço original continua exercitando o estado preenchido, porque resolve a
variável para o Vídeo que acabou de concluir — os dois estados são queries diferentes e mentem
em silêncio de formas diferentes.

**`docs/adr/0004-camada-de-observabilidade.md`** — a decisão de forma da busca de trace, em
§ *Um painel*, passou a carregar os dois estados, o motivo da regex e a nota de que isto
substitui a mitigação por título do 092.

### Medido depois da mudança

| Verificação | Resultado |
|---|---|
| `scripts/smoke.sh` (stack de pé, sob `systemd-inhibit`) | **verde**, saída 0 |
| passo 12, laço | 16 queries, todas com série — a de trace com 1 trace para o Vídeo que concluiu |
| passo 12, passagem nova | **8 traces** com o `idVideo` vazio |
| guarda nova vista **vermelha** | com a query revertida para `= "$idVideo"` num painel de cópia: *"não lista travessia nenhuma com o idVideo VAZIO"* |

A suíte Maven **não** foi rodada, e o motivo é de escopo: a mudança é JSON de painel, Bash e
Markdown, sem uma linha de Java nem de `application.properties` — nada que qualquer cópia do
`ArchitectureConstraintsTest` julgue. Rodá-la exigiria parar o Compose do usuário (os Dev
Services do Keycloak estouram o timeout com a stack de pé), o que apagaria a própria base do
Tempo que serviu de medição.

## Correção (revisão da mesma sessão)

O `/code-review` nos dois eixos rodou contra o commit da Resolução. **Nenhuma violação dura** de
padrão documentado em nenhum dos dois. O que os dois eixos acharam foi o mesmo tipo de coisa: a
guarda nova cobria menos do que o registro prometia. Aplicado:

- **A guarda verde não distinguia perder a âncora.** Achado do eixo Spec, e é o achado que fez a
  revisão valer: a asserção era `achadas > 0`, e perder
  `resource.service.name = "fiapx-extracao"` **aumenta** a contagem — 19 traces contra 8, 11 deles
  sem span do `extracao`. A tela continuaria plausível, listando GET de acompanhamento no lugar das
  travessias, e o critério de aceite "a âncora continua onde está" não tinha quem o cobrasse. O
  passo 12 passou a exigir que **todo** trace listado tenha span do `fiapx-extracao`, pelo
  `serviceStats` da resposta de busca. **Vista vermelha** com a âncora removida de um painel de
  cópia.
- **"A mais nova primeiro" era promessa sem dono.** Nada no JSON impõe ordem — a tabela desenha na
  ordem do quadro, que é a ordem que o Tempo devolveu —, e o `description` afirma a ordem como
  fato. O passo 12 passou a conferir que os `startTimeUnixNano` chegam decrescentes. Esta é a
  única das três asserções que **não** foi vista vermelha: quem a quebraria é uma mudança de
  comportamento do Tempo, que não se simula de fora.
- **O metacaractere na regex ficou dito.** O valor do textbox entra cru em
  `=~ ".*$idVideo.*"`, então um parêntese digitado vira erro de query em vez de filtro. UUID não
  tem metacaractere, e o preço fica aceito — mas agora está no `description` do painel, que é
  quem fala com quem digita, e não só aqui.
- **`| select(.idVideo)` não estava em critério nenhum.** Achado de escopo do eixo Spec, e
  procede: ele entrou pela Resolução, não pelos critérios. Fica, e o porquê é o da própria § acima
  — sem ele, quem expande uma linha da lista recente não tem como ligá-la a um Vídeo. O que muda
  aqui é o registro: é decisão desta sessão, tomada depois dos critérios, não requisito do pedido.
- **Dois resolvedores de variável, e um deles não sabia de `$servico`.** Achado do eixo Standards.
  A passagem nova tinha um `sed` próprio para esvaziar `$idVideo`; um target de trace que usasse
  `$servico` reprovaria com *"variável que este passo não sabe resolver"* — mensagem falsa.
  `resolve_variaveis` passou a aceitar o `idVideo` por argumento, com o Vídeo concluído como
  default, e há um resolvedor só. Na mesma linha, a busca no Tempo virou uma função usada pelas
  duas passagens, e a passagem nova filtra o `$consultas` que o laço já leu em vez de reextrair o
  JSON com um `jq` quase igual.
- **Comentário do mapa reinterpretava o 099.** A nota de fronteira que eu havia escrito
  agrupava 099 e 100 num padrão e repetia *"a fronteira está vazia de novo"* duas vezes seguidas.
  Encurtada para o que é fato desta sessão.

Um achado **não** foi aplicado, e fica registrado: *"travessia"* é usada no título do painel, no
ADR 0004 e neste ticket, e não está no `CONTEXT.md`. O uso é anterior a este ticket (092 e ADR
0004), e acrescentar verbete ao glossário canônico é decisão de modelagem de domínio, não
consequência desta mudança.

`scripts/smoke.sh` rodado de novo, inteiro, depois de todas as edições: **verde** — 16 queries no
laço e a linha nova com as três asserções.
