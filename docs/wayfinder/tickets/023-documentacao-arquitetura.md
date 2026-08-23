# Documentacao de arquitetura

- id: 023
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por: 022

## Question

O enunciado pede "documentacao da arquitetura proposta" como entregavel, e o video de 10
minutos tem de apresenta-la. O repositorio ja documenta muito — tres ADRs, dois contratos,
`AGENTS.md`, `CONTEXT.md`, quatro pesquisas, o mapa inteiro — mas **nada disso e a visao de
conjunto**: cada peca responde uma pergunta local, e a banca chega sem contexto nenhum.

O que falta decidir: qual o artefato (C4 ate que nivel? diagrama de sequencia do fluxo
assincrono? so prosa com o ASCII que ja esta no README?), onde ele vive
(`docs/arquitetura.md`? uma secao do README?), como os diagramas sao produzidos (Mermaid
versionado, imagem exportada, ASCII) e — o mais importante — **o que ele diz que os
documentos existentes nao dizem**, ja que a disciplina do repo e apontar, nunca duplicar.
Vale tambem decidir se as alternativas recusadas com registro (presigned URL, outbox
canonico, JavaCV, Kubernetes) entram, e em que profundidade: elas sao o que separa
"escolhemos X" de "sabemos por que nao Y".

## Resolucao

Entregue: [`docs/arquitetura.md`](../../arquitetura.md), 341 linhas, cinco diagramas Mermaid.

**O que ele diz que os documentos existentes nao dizem.** Essa era a pergunta central, e a
resposta e que os documentos existentes sao todos *locais*: cada ADR, contrato e pesquisa
responde uma pergunta especifica e pressupoe que o leitor ja sabe do que se trata. Nenhum
responde *por que tres servicos e nao um*, *como isto escala*, ou *como o desenho atende os
requisitos do enunciado* — que e exatamente o que uma banca sem contexto procura. O
documento e escrito para ela, nao para o proximo dev, que ja e servido melhor pelo
`AGENTS.md` e pelos contratos.

**Decisoes de forma.** Arquivo proprio em `docs/arquitetura.md` (nao secao do README, cuja
virtude e caber numa tela; nao diretorio, que fragmentaria justamente a visao de conjunto).
Mermaid versionado, nao imagem exportada: renderiza no GitHub e o diff e legivel. O ASCII do
README **fica** — e a unica duplicacao aceita, porque mandar quem so quer rodar abrir outro
arquivo e pior que dez linhas repetidas; e eles contam coisas diferentes (README = caminho
feliz, `arquitetura.md` = containers, camadas, sequencia e falha).

**Cinco diagramas, e o C3 e o que justifica o C4.** C1 magro, C2 de containers, C3 de
componentes **de um servico so** (`videos`) e duas sequencias (feliz e falha). O C3 nao e
enfeite: o desafio abre dizendo que o projeto base esta "sem nenhuma das boas praticas de
arquitetura de software", e a resposta a isso e Clean Architecture — que em C2 e **invisivel**,
porque cada servico e uma caixa opaca. Os tres servicos seriam a mesma figura tres vezes: a
forma e identica por construcao, e o `ArchitectureConstraintsTest` garante isso.

**Achado de renderizacao, nao de especificacao.** O diagrama de containers com `subgraph` para
"negocio" e "infra" renderiza, mas a separacao espacial forcada cruza quase todas as arestas —
verificado renderizando de verdade com `mermaid-cli`, nao so validando a sintaxe. Reescrito
**sem subgraphs**, distinguindo as camadas por `classDef`, o dagre resolve o layout sozinho e
o diagrama fica legivel. Um diagrama que compila nao e um diagrama que comunica.

**Tres afirmacoes minhas estavam erradas e so cairam por conferencia contra o codigo**, o que
justifica a disciplina de nao escrever documento de arquitetura de cabeca:
- o `UPDATE` da guarda de unicidade nao e `WHERE estado <> 'FALHOU'`, e `WHERE id = ? AND
  estado = <predecessor>` — `EstadoVideo.predecessor()` declara o grafo uma vez so, e `FALHOU`
  so e alcancavel de `PROCESSANDO`. A versao errada sugeria uma guarda mais fraca do que a real;
- a DLQ do `extracao` chama-se `extracao.extrair.dlq`, nao `extracao.dlq`;
- a chave do Hibernate e `%prod.quarkus.hibernate-orm.schema-management.strategy=validate`, nao
  `hibernate-orm.database.generation` (nome antigo, que nao existe nesta versao).

**A contagem de testes do mapa estava desatualizada.** Eu ia publicar "128 testes (94 sem
Docker)" somando os numeros registrados nos tickets 015/016/017/014. O real, medido nos logs do
CI verde da `main`, e **130 (83 + 25 + 22)**, dos quais **96 nao sobem container** — 34 exigem
Docker (os tres `CucumberTest` e o `RetryComCompletionStageTest` do `extracao`). Somar numeros
de tickets fechados e derivar, nao medir.

**Achado ambiental, fora do escopo do ticket**: `./mvnw verify` **nao passa nesta maquina**. O
Dev Service do Keycloak reconstroi a imagem a cada execucao (~31s de augmentation) e estoura o
timeout do Testcontainers, de forma reprodutivel — duas execucoes, mesma falha. Nao e regressao
de codigo: o CI da `main` esta verde e o `videos` roda 83 testes la. Vale um ticket proprio se
voltar a incomodar, porque hoje o `verify` local so termina para `extracao` e `notificacao`.

Sem ADR: o documento nao e uma decisao de arquitetura, e uma apresentacao delas. Sem alteracao
em `CONTEXT.md`: nenhum termo novo — o documento consome o glossario, nao o amplia.

Ponteiros adicionados em `README.md` (tabela "Mapa do repositorio") e em `AGENTS.md` ("Onde a
verdade mora"), na mesma disciplina de gatilho do resto do repo.
