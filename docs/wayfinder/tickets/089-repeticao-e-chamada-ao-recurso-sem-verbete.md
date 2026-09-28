# *Repetição* e *chamada ao recurso* são vocabulário canônico sem verbete no glossário

- id: 089
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `a9db71f..e7ced97`, convertido em ticket com aprovação do
usuário. É a lacuna que o [086](086-contagem-de-repeticoes-em-dois-numeros.md) abriu ao fechar:
ele registrou o achado na própria seção `## Revisão` e deixou o verbete por escrever.

## O problema

O `docs/agents/domain.md` diz:

> Use os termos definidos em `CONTEXT.md` em tickets, hipóteses, testes, código e documentação.
> Se um conceito necessário não estiver no glossário, registre a lacuna para `domain-modeling`
> em vez de introduzir um sinônimo sem decisão.

O 086 fez metade disso. Ele tirou *tentativa* de cima da contagem de I/O — que era o defeito — e
pôs no lugar duas palavras que hoje são canônicas em nove arquivos:

- **repetição**: no ADR 0001 (emenda do 086), nos quatro javadocs que implementam a política, no
  nome das constantes (`MAXIMO_DE_REPETICOES`), no nome da chave de configuração
  (`fiapx.armazenamento.espera-entre-repeticoes`, `fiapx.notificacao.espera-entre-repeticoes`),
  no nome de duas classes de teste (`RepeticaoNoMinioTest`, `RepeticaoNoSmtpTest`) e no
  `map.md`;
- **chamada ao recurso**: é a unidade em que o ADR 0001 agora conta a política ("três chamadas
  ao recurso: a primeira mais duas repetições") e em que os três testes fazem a asserção.

Nenhuma das duas tem verbete no `CONTEXT.md`. O glossário define *tentativa* — e o define bem,
porque foi ele que arbitrou o 086 —, mas quem chegar pelo lado do código encontra três palavras
para coisas próximas (*tentativa*, *repetição*, *chamada*) e só uma delas explicada.

O risco não é hipotético: o 086 existe porque duas sessões leram a mesma frase do ADR de dois
jeitos e implementaram os dois. A diferença é que agora as palavras são distintas; o que falta é
o lugar onde essa distinção fica dita uma vez, que é o glossário.

## O que entregar

Verbete no `CONTEXT.md` para os dois termos, na forma dos que já existem — o de *Estacionamento*
é o modelo mais próximo, porque ele nasceu exatamente para separar dois sentidos de uma palavra
sobrecarregada ("terminal").

O que o verbete precisa dizer, no mínimo:

1. **Repetição** é uma nova ida ao mesmo recurso externo (MinIO, SMTP, Postgres) dentro de
   **uma** tentativa, depois que a primeira ida falhou de forma transitória. Não gasta entrega
   nenhuma, não aparece na fila e não é observável de fora do serviço.
2. **Chamada ao recurso** é a unidade em que a política do ADR 0001 se conta: a primeira ida
   mais as repetições. Três chamadas é a política; `atMost(2)` é como o Mutiny a escreve.
3. A relação com *tentativa*, que fica **intocada**: uma tentativa é uma entrega do trabalho ao
   `extracao`, e cada tentativa pode gastar várias chamadas ao recurso. Os dois limites valem 3
   por coincidência, e é essa coincidência que já custou um ticket.

Onde encaixar é decisão de quem executar: § *Extração* já hospeda *tentativa* e é onde o leitor
procura, mas repetição não é conceito de Extração — ela acontece igual no `videos`, que não
executa Extração nenhuma. Uma seção própria, curta, provavelmente serve melhor; se ficar em
§ *Extração*, o texto precisa dizer que o conceito não é exclusivo dela.

O que **não** entra: mudança de comportamento, de constante ou de teste. O 086 já decidiu os
números; este ticket só dá nome ao que ele decidiu.

## Critérios de aceite

- [x] `CONTEXT.md` define *repetição* e *chamada ao recurso*, e a definição concorda com o que o
      ADR 0001 e os quatro javadocs fazem hoje
- [x] O verbete de *tentativa* continua valendo palavra por palavra: nada do texto novo disputa
      o sentido dele, e a relação entre os dois está dita
- [x] A coincidência dos dois limites em 3 está registrada como coincidência, com o ponteiro
      para o [086](086-contagem-de-repeticoes-em-dois-numeros.md)
- [x] Nenhuma constante, contagem ou teste mudou
- [x] `./mvnw test` verde a partir da raiz

## Resolução

**Seção própria, e não um parágrafo em § *Extração*.** O ticket deixou a decisão para quem
executasse, com o argumento pronto dos dois lados; escolhi a seção própria porque repetição
não tem dono. O `videos` não executa Extração nenhuma e repete do mesmo jeito ao falar com o
MinIO e com o Postgres — hospedar o conceito em § *Extração* daria a ele um dono que ele não
tem, e a nota de rodapé que o ticket previa ("o conceito não é exclusivo dela") seria uma
correção permanente do próprio lugar onde o texto estaria. A seção nova, `## Repetição e
chamada ao recurso`, fica **entre** § *Extração* e § *Estacionamento*: adjacente a *tentativa*,
que é de onde o leitor chega, sem ficar dentro dela.

**O que o verbete diz.** Repetição é uma nova ida ao mesmo recurso externo — MinIO, SMTP,
Postgres — dentro de **uma** tentativa, depois que a ida anterior falhou de forma transitória;
não gasta tentativa, não aparece na fila, não é observável de fora do serviço. Chamada ao
recurso é a unidade em que a política se conta: a primeira ida mais as repetições, três ao
todo.

**A aritmética não veio junto, de propósito.** O `CONTEXT.md` declara de si, na primeira linha,
que é "só glossário". O verbete diz *o que* três chamadas ao recurso são e aponta para o
[ADR 0001](../../adr/0001-politica-de-falhas.md) para a aritmética, o motivo do número e o
`atMost(2)` do Mutiny — que é onde a emenda do 086 já os escreveu **uma vez**, e onde os quatro
javadocs já vão buscá-los. Duplicá-los aqui recriaria, em outro arquivo, exatamente a
duplicação que o 086 fechou.

**A relação com *tentativa*, que ficou intocada.** Nenhuma palavra do verbete de *tentativa*
mudou. O texto novo declara a relação por escala — uma tentativa é uma entrega ao `extracao`, e
cada tentativa pode gastar várias chamadas ao recurso, inclusive uma tentativa que morre no
meio — e registra os dois limites em 3 como **coincidência**, com o ponteiro para o
[086](086-contagem-de-repeticoes-em-dois-numeros.md): três entregas é o `x-delivery-limit` da
fila, três chamadas é o que um adapter faz antes de desistir, e um pode mudar sem o outro.

**Nenhum código mudou.** `git diff --stat` contra o ponto de partida (`c0bca90`) traz três
arquivos, todos Markdown: `CONTEXT.md`, `docs/wayfinder/map.md` e este ticket. Nenhuma
constante, nenhuma contagem, nenhum teste.

**Validação.** `./mvnw test` a partir da raiz: **BUILD SUCCESS**, 452 testes — 143 no
`videos`, 280 no `extracao`, 29 no `notificacao`, com os quatro testes de repetição verdes
(`RepeticaoNoPostgresTest`, `RepeticaoNoMinioTest`, `RepeticaoNoSmtpTest` e o
`EnvioResisteABlipDoArmazenamentoTest`, dentro da suíte do `videos`).
`scripts/verifica-testes-arquiteturais.sh` e `scripts/verifica-ackmanual.sh` passaram.
`smoke.sh` e os ensaios de carga não foram executados: a mudança é de Markdown e não toca
contrato, mensageria, Compose nem imagem.

## Revisão

Revisão de dois eixos sobre `c0bca90...d8a8f57`. Quatro achados aplicados, um recusado, um
levado ao usuário.

**Aplicados, os três do eixo Spec, e todos do mesmo tipo — o verbete afirmava mais do que o
código faz:**

1. *"dentro de uma tentativa" não vale no `videos`.* A borda HTTP não consome entrega nenhuma,
   e o próprio `RepeticaoNoPostgres` diz que uma indisponibilidade longa "deve voltar ao
   **consumidor HTTP** ou ao mecanismo de reentrega da fila". A frase passou a "dentro de uma
   unidade de trabalho": uma tentativa onde há tentativa, a própria requisição na borda. O
   defeito era herdado — os javadocs do `videos` dizem "dentro de uma tentativa" desde o 086, e
   o verbete copiou a frase do serviço errado.
2. *"repete do mesmo jeito ao falar com o MinIO e com o Postgres"* apagava exatamente a
   divergência que o [087](087-postgresretry-diverge-das-copias-de-comrepeticao.md) registrou
   como deliberada: o filtro de falha. Ganhou parágrafo próprio, com ponteiro para o `AGENTS.md`
   § *As cópias deliberadas entre serviços*.
3. *"falhou de forma transitória"* descrevia a intenção da política, não o filtro implementado —
   as três cópias de `comRepeticao` repetem qualquer `Exception`. Resolvido pelo mesmo parágrafo
   do ponto 2: o que conta como falha passageira é decisão de cada recurso.

**Aplicado, do eixo Standards:** o parágrafo da coincidência citava `x-delivery-limit` e "o que
um adapter faz antes de desistir" — implementação, que a linha 3 do próprio `CONTEXT.md` manda
morar no mapa e nos ADRs. Saíram; a coincidência e o ponteiro para o 086 ficaram, porque o
critério de aceite 3 os cobra nominalmente. Junto: as cinco caixas dos critérios foram marcadas,
que é como o 084–088 fecham.

**Recusado, do eixo Standards:** que o número "três chamadas" no verbete duplique o ADR. Ele é o
valor do termo que a seção define, e o critério de aceite 1 cobra que a definição *concorde* com
o ADR — concordar sem dizer o número deixaria o verbete sem conteúdo. O que ficou fora é a
aritmética (`atMost(2)`, o porquê de três e não quatro), que continua só no ADR.

**Levado ao usuário:** o eixo Spec observou que "O que entregar" item 2 pede literalmente
"`atMost(2)` é como o Mutiny a escreve", e o verbete só aponta para o ADR. É redução consciente
do mínimo pedido, pelo motivo do parágrafo acima, e fica declarada aqui em vez de silenciosa.

**Um achado menor, conferido e não aplicado:** "não é observável de fora do serviço" foi
questionado contra o ADR 0004 — spans poderiam expor a ida repetida. O `Rastro` do `videos`
responde que não: "nenhum span de S3 chegou ao Tempo [...] vão mudo dentro do span do POST". A
frase saiu mesmo assim, por outro motivo — foi substituída por três fatos conferíveis (não gasta
tentativa, não aparece na fila, não muda o estado do Vídeo), que é o que ela queria dizer.

**Revalidação.** `./mvnw test` não foi reexecutado depois desta revisão: os quatro ajustes são
de Markdown, no mesmo arquivo de glossário, e nenhum arquivo Java ou `.properties` foi tocado em
nenhum dos dois commits.
