# A pasta *FIAP X* aparece vazia no Grafana, e o painel curado mora fora dela

- id: 099
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-12)
- bloqueado-por:
- prioridade: P3

## Origem

Sessão de 2026-09-12, diagnóstico pedido assim: *"o diretório FIAP X está vazio no grafana, seria
melhor excluir do json?"*.

## Conflito com o registro, primeiro

O pedido supõe que a pasta venha de um JSON, e ela não vem de JSON nenhum: quem a declara é
`docker/observabilidade/alertas.yaml:26` (`folder: FIAP X`), onde ela agrupa as **três regras de
alerta**. O comentário de `docker/observabilidade/dashboards.yaml` dizia o oposto do que o pedido
pede — *"Pasta raiz de propósito"*, com a ressalva de que a pasta do `alertas.yaml` *"é outra
coisa"*. Este ticket reverte aquela escolha; a ressalva é que estava errada, e é o achado.

## O que foi medido

Com o Compose de pé, pela API do Grafana (acesso anônimo com papel Admin, sem credencial):

| Verificação | Resultado |
|---|---|
| `GET /api/folders` | **uma** pasta: `FIAP X` (`afy0z7yxy9vy8c`) |
| `GET /api/search?type=dash-db&folderUIDs=afy0z7yxy9vy8c` | `[]` — nenhum dashboard |
| `GET /api/v1/provisioning/alert-rules` | as **três** regras, todas com `folderUID` daquela pasta |
| `folderUid` dos quatro dashboards | `None` nos quatro — o curado e os três de fábrica estão na raiz |

Ou seja: a pasta não está vazia, está vazia **de dashboard**. No Grafana, pasta de alerta e pasta
de dashboard são a mesma entidade, então ela aparece na lista de Dashboards carregando só regras.
O `name: "FIAP X"` do `dashboards.yaml` é o nome do **provider**, e provider não cria pasta.

## Os caminhos, e o preço de cada um

1. **Excluir a pasta.** Não existe: `folder` é obrigatório em regra de alerta provisionada, e
   apagá-la do `alertas.yaml` derruba os três alertas. Do JSON não há o que excluir.
2. **Deixar como está.** Pasta com só alerta é o normal do Grafana. Preço: um item aparentemente
   vazio na lista, que confunde quem abre na demo — foi exatamente o que aconteceu.
3. **Mover o painel curado para dentro dela** (`folder: "FIAP X"` no `dashboards.yaml`). A pasta
   deixa de parecer vazia, e o painel passa a morar ao lado dos três alertas de que ele **deriva
   as expressões de fila** — a decisão de forma que o ADR 0004 § *Um painel* já registra. Preço: o
   clique a mais na busca que o 092 evitava.

**Escolhido o 3.** O preço é quase nulo porque a home não depende de pasta:
`GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` lê **arquivo do disco**, e quem abre `localhost:3000`
continua caindo no painel sem navegar. O argumento de "raiz de propósito" do 092 foi escrito para
o painel ser fácil de achar; com a home entregando isso, o que sobra da raiz é só a pasta vazia.

## Critérios de aceite

- [x] `folder: "FIAP X"` no `dashboards.yaml`, com o comentário dizendo por que é a mesma pasta do `alertas.yaml`
- [x] Numa stack recém-subida, a pasta lista o painel curado **e** as três regras continuam nela
- [x] A home do Grafana continua sendo o painel
- [x] `scripts/smoke.sh` verde

## Resolução

`docker/observabilidade/dashboards.yaml` passou de `folder: ""` para `folder: "FIAP X"`, e o
comentário que defendia a raiz foi substituído pelo motivo da pasta. Nada mais mudou — nenhuma
query, nenhum JSON de painel, nenhum mount, nenhum código.

### Medido depois da mudança, em container recriado

| Verificação | Resultado |
|---|---|
| `GET /api/folders` | continua **uma só** pasta `FIAP X` — o provider casa por **título** e reaproveita a do `alertas.yaml`, não cria uma segunda |
| dashboards na pasta | `fiapx-infraestrutura` — *FIAP X — a infraestrutura está saudável?* |
| alertas na pasta | as três, com o `folderUID` da mesma pasta |
| `meta` do dashboard | `folderTitle: FIAP X`, `provisioned: true`, 14 painéis |
| `GET /api/dashboards/home` | redireciona para o painel, como antes |
| `scripts/smoke.sh` | **verde**, incluindo o passo 12 — 16 queries do painel, todas com série |

### O achado do caminho: `restart` não move o painel, `up --force-recreate` move

Medido nesta sessão, e vale registrar porque custou uma rodada: depois de um
`docker compose restart observabilidade`, o log diz `starting`/`finished to provision dashboards`
e mesmo assim o painel **continua na raiz**. O provisionador de arquivo pula o dashboard cujo
arquivo não mudou, e o `painel-infraestrutura.json` não mudou — só o provider mudou. Quem move é
a base de dados nova: o Grafana desta imagem guarda a dele dentro do container (não há volume para
ela), então `up -d --force-recreate observabilidade` provisiona do zero e o painel nasce na pasta.
Numa stack subida do zero, que é o caso da demo, a pergunta não aparece.

Isto tem uma consequência operacional: quem editar `dashboards.yaml` de novo e conferir por
`restart` vai ler que a mudança não pegou, e ela pegou.

### Uma reprovação do `smoke.sh`, e por que não é desta mudança

A primeira rodada depois do `--force-recreate` reprovou no passo 12, no painel *Borda — recusas do
contrato (4xx), por status*: `rate(...[5m])` precisa de duas amostras na janela, e a base de
métrica tinha acabado de ser zerada junto com o container — as recusas que o próprio smoke gera
tinham só uma amostra dentro da janela quando o passo 12 rodou. As séries `400`, `401`, `404`,
`409` e `415` estavam na base, conferidas por consulta direta. A rodada seguinte, com a base já
povoada, passou com as 16 queries. É fragilidade de janela numa base recém-zerada, não regressão:
nada nesta mudança toca query, e a queixa é do numerador do 097, não da pasta.

## Correção (revisão da mesma sessão)

O `/code-review` nos dois eixos rodou contra a Resolução acima. **Nenhuma violação dura** em
nenhum dos dois; três achados, e os três convergem no mesmo ponto — o que este ticket descobriu
ficou só no ticket, e ticket fechado não é documentação do estado atual (`TRACKER.md`). Aplicados
no mesmo commit:

- **O título da pasta virou contrato entre dois arquivos, e sem guarda.** `folder: "FIAP X"` no
  `dashboards.yaml` tem de bater letra por letra com o `folder: FIAP X` do `alertas.yaml`;
  divergindo, o Grafana cria uma **segunda** pasta em silêncio e o defeito do 099 volta inteiro —
  ninguém levanta erro. O repositório tem precedente para os dois caminhos (guardar, como
  `verifica-ackmanual.sh`; ou declarar sem guarda, como as famílias do `AGENTS.md`), e aqui a
  escolha é guardar, pelo mesmo argumento do ADR 0004 § *Um painel*: o que pode mentir em silêncio
  ganha quem o cobre. O passo 12 do `scripts/smoke.sh` passou a ler o título **dos dois arquivos**,
  em vez de fixá-lo, e a conferir que o painel está naquela pasta no Grafana e que as três regras
  provisionadas apontam para o mesmo `folderUid` — é este segundo teste que distingue "a pasta
  certa" de "uma homônima".
- **O aviso do `restart` não chegava a quem edita.** Ele vivia só aqui e no mapa; foi para o
  comentário do `dashboards.yaml`, que é o arquivo que o próximo editor abre, e onde o repositório
  já guarda armadilha desse tipo.
- **O critério 2 foi medido por `up -d --force-recreate`, não por `down`/`up`.** O que ele exige —
  base do Grafana nova — o `--force-recreate` entrega, porque esta imagem guarda a base **dentro do
  container** e não há volume para ela; mas o critério diz "stack recém-subida", e o que foi medido
  está escrito na tabela. Fica como está, com a diferença dita aqui.

`scripts/smoke.sh` rodado de novo depois das edições: **verde**, com a linha nova
(*o painel e os alertas dividem a pasta 'FIAP X'*) e as 16 queries do passo 12. A guarda também foi
vista **vermelha**, com o título divergente de propósito num dos dois arquivos — guarda que nunca
reprovou não é guarda.
