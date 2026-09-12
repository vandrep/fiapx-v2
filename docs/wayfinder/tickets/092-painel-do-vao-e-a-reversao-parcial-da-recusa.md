# Um painel do vão, e a reversão parcial da recusa de painel curado

- id: 092
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-10)
- bloqueado-por: 091
- prioridade: P3

## Origem

Sessão de 2026-09-09. O pedido foi *"painéis que ajudem a mostrar como o sistema está, além de
poder ajudar a avaliar logs e traces"*, e ele bate de frente com o registro: **painel curado**
está em [`map.md`](../map.md) § *Fora de escopo*, e o
[ADR 0004](../../adr/0004-camada-de-observabilidade.md) o recusa por mérito, com dois argumentos
— *"a exploração ad-hoc no Explore responde as mesmas perguntas sem manutenção, e um painel é a
parte que envelhece primeiro"* e *"na demo ele seria pior ainda — um painel vazio prova menos que
uma busca por `idVideo` que devolve os três serviços"*.

Este ticket é, portanto, uma **reversão parcial** de decisão registrada, e não um acréscimo. O
que mudou desde a recusa está na seção seguinte.

## O que derruba a recusa, e o que dela continua de pé

A recusa não cai por vontade; cai porque um dos seus dois argumentos pressupõe um observador que
o projeto não tem.

**Cai o argumento do Explore.** "A exploração ad-hoc responde as mesmas perguntas" pressupõe
alguém que **sabe o que perguntar**. O público real desta entrega é o avaliador, nos dez minutos
do vídeo de demonstração, e ele não sabe que existe uma fila chamada
`extracao.extrair.estacionamento` — não tem como formular a consulta que o Explore responderia. A
diferença que um painel faz aqui não é de eficiência: é entre "está tudo verde" e "não sei o que
perguntar".

**Fica de pé o argumento do envelhecimento**, e por isso ele vira requisito deste ticket em vez de
ser dispensado — ver § *O que impede o painel de envelhecer*.

**Fica de pé, e reforçado, o "painel vazio prova menos".** Foi medido nesta sessão: a métrica
`fiapx.extracao.duracao` **não tem série nenhuma no Prometheus** antes da primeira Extração — 88
nomes de métrica na base, zero com `durac`. Um painel dela numa stack recém-subida é literalmente
vazio, e isso condiciona o passo de verificação abaixo.

## Escopo: só o vão

O [091](091-series-otlp-sem-instance-cegam-os-dashboards-de-fabrica.md) faz *RED Metrics
(classic)* e *JVM Overview*, que a imagem já mantém, passarem a responder HTTP e JVM dos três
serviços. **Isso sai do escopo deste painel**, e é a razão de este ticket estar bloqueado por
aquele: repetir aqui séries que outro dashboard já mantém é exatamente o envelhecimento que o ADR
0004 teme, com o agravante de que o dono do outro dashboard é a imagem, que muda sozinha no
upgrade.

Sobra o que nada de fábrica olha:

- **Fila** — profundidade, prontas, não-confirmadas e contagem de consumidores, por fila. São as
  mesmas séries `rabbitmq_detailed_queue_*` que os três alertas de
  `docker/observabilidade/alertas.yaml` já usam, e a expressão deve ser derivada delas, não
  reinventada: duas verdades sobre a mesma pergunta é como o painel começa a divergir.
- **Estacionamento e DLQ** — as duas filas terminais, em destaque, porque mensagem nelas é sempre
  trabalho humano pendente ([`CONTEXT.md`](../../../CONTEXT.md) § *Estacionamento*).
- **`fiapx.extracao.duracao`** — a única métrica nossa, com o atributo `resultado`
  (`concluida`/`falhou`). O atributo não é decoração: sem ele, uma Extração que morre no teto de
  300 s entra na mesma distribuição das que terminaram.
- **Log** — stream filtrado por `service_name`, sobre o datasource `loki`.
- **Trace** — busca por `idVideo`, sobre o datasource `tempo`.

Fora do escopo, explicitamente: **contagem de Vídeo por estado**. O ADR 0004 já recusou o gauge
("o endpoint de listagem já responde, e um gauge exigiria varredura periódica no banco"), e
reabrir isso é outro ticket, com código novo no `videos`.

## A busca de trace precisa da âncora

O ADR 0004 já registra o defeito, medido: buscar só por `idVideo` casa **dezenas de traces de uma
linha**, porque o atributo marca também o span de cada GET de acompanhamento, e o primeiro
resultado costuma ser uma consulta, não a travessia. O passo 10 do `scripts/smoke.sh` ancora em
`resource.service.name = "fiapx-extracao"` por causa disso, e a checagem reprovou de verdade antes
de ganhar a âncora. O painel herda a mesma âncora, e pelo mesmo motivo. Vale a segunda ressalva do
mesmo ADR: nem todo span da travessia carrega `idVideo` — o do `ffmpeg` não —, então casar os dois
exige **dois spansets ligados por `&&`**, não duas condições dentro de um.

## O que impede o painel de envelhecer

Um passo novo no `scripts/smoke.sh`, no espírito dos passos 10 e 11: consultar cada query do
painel e reprovar se alguma devolver série vazia num sistema que acabou de processar um Vídeo. É a
resposta direta ao argumento que sobrou da recusa, e é o que este repositório já faz com tudo que
pode mentir em silêncio — as três cópias do teste arquitetural, o `SdkDesligadoAindaGravaTest`, o
próprio passo 11.

**O passo tem de rodar depois do ciclo completo**, não antes: `fiapx.extracao.duracao` está
legitimamente vazia até a primeira Extração, e um passo posto cedo demais reprova um sistema
saudável.

## Decisões já tomadas nesta sessão

| | Decisão |
|---|---|
| Reversão | **parcial** — um painel, não uma suíte |
| Público | o avaliador, nos dez minutos do vídeo; o desenvolvedor é efeito colateral |
| Pergunta que responde | *"a infraestrutura está saudável?"* — fila, Estacionamento, DLQ, consumidores — mais a duração da Extração |
| Provisionamento | por arquivo, ao lado do `alertas.yaml`. Custa **dois** mounts: o JSON e um provider próprio em `provisioning/dashboards/` (o `sample.yaml` da imagem está todo comentado) |
| Home do Grafana | sim, via `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` no serviço `observabilidade`. Sem isso o painel é mais um item numa lista de quatro, e o avaliador abre o RED por engano |
| Títulos | acentuados — **Extração**, do `CONTEXT.md`. `uid` e nome de arquivo em ASCII |
| Datasources | `prometheus`, `loki`, `tempo` — uids verificados e estáveis na imagem |

## Onde a reversão fica registrada

Três lugares, e o `TRACKER.md` § *Onde mora a reversão de uma decisão* decide os dois primeiros:

1. **`map.md`** — linha em *Decisões até aqui*, e o item de *Fora de escopo* ganha um ponteiro
   para este ticket. O item **não se reescreve**: painel curado esteve fora, e por que esteve
   continua sendo parte do registro.
2. **Este ticket**, que é o registro do que mudou.
3. **`ADR 0004`** — seção nova no fim. A frase *"Painel curado no Grafana foi recusado e continua
   fora"* deixa de ser verdadeira e ganha um ponteiro de uma linha, **sem ser reescrita**: é a
   mesma política de ticket fechado, e o parágrafo é registro do que se decidiu na época. Um ADR
   0005 só sobre um painel fragmentaria o assunto da camada, que é o que o 0004 existe para
   manter junto.

## Critérios de aceite

- [x] Um painel, provisionado por arquivo, versionado em `docker/observabilidade/`
- [x] Ele é a home do Grafana: abrir `localhost:3000` cai nele sem navegar
- [x] Cobre fila, Estacionamento, DLQ, consumidores e `fiapx.extracao.duracao` com `resultado`
- [x] Não repete HTTP nem JVM; linka para os dois dashboards que o 091 fez funcionar
- [x] As expressões de fila são derivadas das do `alertas.yaml`, não reinventadas
- [x] A busca de trace ancora em `resource.service.name` e usa dois spansets ligados por `&&`
- [x] Passo novo no `smoke.sh`, **depois** do ciclo do Vídeo, reprova query que devolve vazio
- [x] O passo 11 continua verde: derrubar a stack de observabilidade não afeta o ciclo do Vídeo
- [x] `map.md`, este ticket e o `ADR 0004` registram a reversão, sem reescrever o que foi recusado

## Resolução

Feito como especificado, sem desvio de escopo. O que o ticket não previa e a implementação
mediu está em *O que a medição mudou no desenho*, abaixo.

### O que mudou

| Arquivo | Mudança |
|---|---|
| `docker/observabilidade/painel-infraestrutura.json` | **novo**. Um painel, `uid` `fiapx-infraestrutura`, título acentuado (*FIAP X — a infraestrutura está saudável?*), nome de arquivo e `uid` em ASCII. Onze painéis: quatro *stat* de cabeça (Estacionamento, DLQs, filas sem consumidor, consumidores da `extracao.extrair`), três séries de fila, dois de `fiapx.extracao.duracao`, um de busca de trace e um de log. Como JSON não carrega comentário, o porquê de cada painel vive no `description` dele — que é tooltip na tela, e portanto chega também a quem só assiste à demo |
| `docker/observabilidade/dashboards.yaml` | **novo**. O provider de arquivo. Ele existe porque `provisioning/dashboards/` guarda *providers*, não dashboards: um JSON solto ali é ignorado, e o `sample.yaml` da imagem está inteiro comentado. `allowUiUpdates: false` e `disableDeletion: true` deixam o arquivo ser a verdade |
| `docker-compose.yml` | dois mounts novos no serviço `observabilidade` (o provider e o JSON, este num subdiretório `fiapx/`) e o bloco `environment` com `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH`. O `grafana-dashboards.yaml` da imagem não é tocado: um provider a mais convive com ele, e sobrescrevê-lo custaria rederivar aquele arquivo a cada upgrade |
| `scripts/smoke.sh` | passo **12** novo, depois do 11. Ele lê as queries **do arquivo do painel** e reprova a que devolver série vazia; confere de quebra que a home do Grafana é o painel e que os três `uid` de datasource que o painel fixa existem. O cabeçalho do script passa a apresentar o passo, e a linha final diz que o painel é a home |
| `docs/adr/0004-camada-de-observabilidade.md` | ponteiro de uma linha no parágrafo da recusa, **sem reescrevê-lo**, e seção nova no fim: *Um painel, e o que da recusa continua de pé* |
| `docs/wayfinder/map.md` | linha em *Decisões até aqui*; o item de *Fora de escopo* ganhou o ponteiro e **não** foi reescrito |
| `docs/arquitetura.md` | duas frases que passaram a ser falsas: a de que painel curado continua fora, e a linha da tabela de recusados. A linha ganhou o ponteiro em vez de sumir — o canal de notificação continua recusado ali |
| `README.md` | *"Não há painel montado"* era falso. O parágrafo passa a dizer o que abre em `localhost:3000`, e por que HTTP e JVM não estão lá |

### O que a medição mudou no desenho

**A duração virou média por `resultado`, e não quantil.** O desenho inicial era
`histogram_quantile` sobre `rate(..._bucket[$__rate_interval])`, que é a forma de manual — e ela
devolveu `NaN` em **todas** as amostras da janela de uma hora. Duas causas independentes, as
duas medidas contra a stack:

- Os limites de bucket são os *default* do OpenTelemetry (0, 5, 10, 25 … 10000), pensados para
  milissegundos. A Extração do fixture leva **0,19 s** (concluída) e **0,048 s** (falha no
  ffprobe): as três observações caem no primeiro bucket, e o quantil ali é interpolação linear
  dentro de `[0, 5]`, não medida.
- No volume da demo, `rate()` sobre a janela devolve zero: a série nasce já com a contagem — o
  exportador só emite o instrumento depois da primeira observação —, então não há incremento
  **dentro** da janela para o `rate` enxergar, e `histogram_quantile` sobre buckets todos em zero
  é `NaN`.

`_sum / _count` por `resultado` é exato nos dois casos, e preserva o corte que é o ponto da
métrica. Mudar os limites de bucket seria código novo no `extracao`, e outro ticket.

**A verificação precisou separar "sem série" de "série só de `NaN`".** A primeira versão do passo
12 cobrava `.data.result | length > 0`, e a query do quantil **passava** — duas séries, todas as
amostras `NaN`. Um painel que desenha uma linha vazia é o mesmo defeito que este passo existe
para pegar. O passo conta amostras não-`NaN`, e foi essa contagem que expôs o problema acima.

**Dois defeitos de escape, achados por reprovação e não por leitura.** A expressão das DLQs é
`queue=~".+\\.dlq"` — PromQL usa escape de Go em literal de string, então a contrabarra vai
dobrada, e a forma "óbvia" (`.+\.dlq`) é erro de sintaxe. E o passo 12 lia as queries com
`@tsv` do `jq`, que **escapa contrabarra**: a expressão chegava ao Prometheus com a barra
dobrada de novo e não casava fila nenhuma, reprovando um painel correto. O passo lê com
`join` num separador de unidade.

### Como foi verificado

`scripts/smoke.sh` inteiro, do zero (`docker compose down` antes), sob `systemd-inhibit`:
**verde nos doze passos**, com o 11 inalterado. O passo 12 consultou as **12** queries do painel
— as nove do Prometheus, a do Loki e a do Tempo, mais a variável `$servico` resolvida no
`allValue` e o `$idVideo` resolvido no Vídeo que concluiu — e todas devolveram amostra. O
provisionamento foi conferido pela API do Grafana (13.2.0): o painel aparece como `provisioned`,
com os onze painéis, as duas variáveis e os dois links, e `GET /api/dashboards/home` redireciona
para ele.

## Revisão aplicada

Revisão em dois eixos (padrões e spec) sobre o commit de implementação. Nove achados viraram
mudança; três foram respondidos com registro em vez de código, e um foi recusado.

**Mudou o `smoke.sh`, passo 12.** Quatro defeitos reais, nesta ordem de gravidade:

- **Aborto silencioso.** As quatro atribuições `x="$(curl … | jq …)"` rodavam sem `|| true` sob
  `set -euo pipefail`: datasource fora do ar matava o script sem uma linha de `falha`. Os passos
  2 e 10 já faziam certo, e o 12 agora faz igual — e distingue "não respondeu um número" de
  "respondeu vazio", que são defeitos diferentes.
- **Duas verdades.** O passo resolvia `$servico` para `fiapx-.+` escrito à mão, contra o
  argumento do comentário três linhas acima. Ele lê o `allValue` **do arquivo do painel**.
- **Ramo morto.** A substituição de `$__rate_interval` sobreviveu à troca de quantil por média e
  não casava nada, com uma justificativa de aparência viva. No lugar dela entrou uma guarda que
  vale mais: query que chegue ao datasource ainda com `$` reprova, dizendo qual variável o passo
  não sabe resolver — variável nova no painel não passa mais em silêncio.
- **Comentário fora de lugar** e mensagens sem acento, ao contrário das dos passos 10 e 11.

**Mudou o painel.** `editable` era `true` com `allowUiUpdates: false` no provider — prometia um
botão de salvar que não existe. E o painel de trace passou a se chamar *Trace da travessia —
preencha o idVideo no topo*: com o textbox vazio na primeira abertura, a tabela vazia lia como
painel quebrado em vez de campo por preencher.

**Não mudou, e ficou escrito por quê.** Três achados eram de registro, não de código:

- *"As expressões de fila são derivadas das do `alertas.yaml`"* — dois painéis divergem dos
  alertas de propósito, e a divergência agora está no `description` de cada um em vez de
  implícita. O de DLQs é a **união** de dois alertas (o segundo nomeia só a do `extracao`, que é
  a única com consumidor; as terminais são do terceiro), porque um stat que mostrasse só uma
  deixaria mensagem parada nas outras duas fora da tela. O de consumidores da `extracao.extrair`
  nomeia a fila sem ter alerta que a nomeie, porque o número saudável é **dois** — um por réplica
  — e isso é o que ninguém de fora sabe conferir; a pergunta sem nome de fila é a do painel ao
  lado.
- *"reprova query que devolve vazio"* — o `count(…) or vector(0)` **nunca** volta vazio e
  portanto escapa do passo 12. Medido: `sum(…{queue="fila-que-nao-existe"})` e um seletor de fila
  inexistente voltam vazios e reprovam; só o `or vector(0)` não. O que ficaria descoberto são as
  duas métricas que ele usa, e as duas aparecem **cruas** em dois outros painéis que o passo
  cobra — renomeada qualquer uma no upgrade do broker, quem reprova são eles. Está no
  `description` do painel e no cabeçalho do passo.
- Erro de conta na § *Como foi verificado*: eram **dez** queries do Prometheus, não nove.
  Corrigido no lugar, e não por seção nova, porque o parágrafo é desta mesma sessão e ninguém o
  leu antes.

**Recusado:** a observação de que editar `docs/arquitetura.md` e `README.md` extrapola os "três
lugares" da § *Onde a reversão fica registrada*. Aqueles três lugares são onde a **reversão**
fica registrada; os dois arquivos continham afirmações que a mudança tornou **falsas** — *"Não há
painel montado"* e *"o que continua de fora é painel curado e canal de notificação"*. Deixá-las
seria trocar um painel que mente por uma documentação que mente. A linha da tabela de recusados
do `arquitetura.md`, essa sim é registro de decisão, e ganhou ponteiro sem ser reescrita.

## Correção (092)

Dois erros da sessão que fechou este ticket, achados pela segunda rodada de revisão.

**O primeiro é de conta.** A § *Como foi verificado* diz *"as nove do Prometheus, a do Loki e a
do Tempo"*, e são **dez** do Prometheus — o painel *Fila — prontas e não-confirmadas* tem dois
targets, e o segundo não foi contado. Dez mais uma mais uma é o total de doze que a mesma frase
dá, e que o passo 12 imprime.

**O segundo é o de como o primeiro foi corrigido.** A § *Revisão aplicada* registra a troca de
"nove" por "dez" feita **dentro** da `## Resolução`, com a justificativa de que *"o parágrafo é
desta mesma sessão e ninguém o leu antes"*. O
[`TRACKER.md`](../TRACKER.md) § *O que pode mudar num ticket `fechado`* não abre essa exceção —
*"Corpo narrativo e `## Resolução` já escritos: **não se reescrevem nem se apagam**"*, e *"erro
de fato descoberto depois se corrige por seção nova"*. A justificativa era exatamente o tipo de
argumento que a regra existe para barrar: quem a aceita uma vez a aceita sempre, porque toda
correção parece pequena e recente para quem a faz. A troca foi desfeita, o parágrafo voltou a
dizer "nove", e a conta certa é esta seção. A § *Revisão aplicada* também fica como está, pelo
mesmo motivo — inclusive a linha que anuncia a correção no lugar, que é o registro de uma decisão
que se mostrou errada.

---

*A escolha de deixar o painel na pasta raiz foi revertida pelo
[ticket 099](099-a-pasta-fiap-x-vazia-e-o-painel-que-mora-fora-dela.md): ele vive na pasta
`FIAP X`, a mesma dos três alertas.*
