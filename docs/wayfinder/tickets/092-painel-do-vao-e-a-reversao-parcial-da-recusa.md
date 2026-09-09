# Um painel do vão, e a reversão parcial da recusa de painel curado

- id: 092
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Um painel, provisionado por arquivo, versionado em `docker/observabilidade/`
- [ ] Ele é a home do Grafana: abrir `localhost:3000` cai nele sem navegar
- [ ] Cobre fila, Estacionamento, DLQ, consumidores e `fiapx.extracao.duracao` com `resultado`
- [ ] Não repete HTTP nem JVM; linka para os dois dashboards que o 091 fez funcionar
- [ ] As expressões de fila são derivadas das do `alertas.yaml`, não reinventadas
- [ ] A busca de trace ancora em `resource.service.name` e usa dois spansets ligados por `&&`
- [ ] Passo novo no `smoke.sh`, **depois** do ciclo do Vídeo, reprova query que devolve vazio
- [ ] O passo 11 continua verde: derrubar a stack de observabilidade não afeta o ciclo do Vídeo
- [ ] `map.md`, este ticket e o `ADR 0004` registram a reversão, sem reescrever o que foi recusado
