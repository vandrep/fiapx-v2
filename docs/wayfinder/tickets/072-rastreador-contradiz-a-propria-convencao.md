# O rastreador contradiz a própria convenção

- id: 072
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...39712e7`, convertido em ticket com aprovação do
usuário.

## O problema

`docs/wayfinder/TRACKER.md` e `docs/agents/issue-tracker.md` definem duas operações de consulta,
e as duas se apoiam no campo `status`: a **fronteira** é `status: aberto` sem bloqueio e sem
`assignee`; **resolver** é `status: fechado` com seção `## Resolução` e uma linha em "Decisões
até aqui" no mapa. Três desvios quebram isso hoje:

1. **Seis tickets em `status: resolvido`** — 038, 039, 040, 041, 042 e 056. O valor não existe na
   convenção. A consequência é concreta e é pior do que inconsistência cosmética: eles não casam
   nem a consulta de fronteira nem a de resolvido, então somem das duas. Um agente que pergunte
   "o que falta?" não os vê, e um humano que pergunte "o que já foi feito?" também não.
2. **O 038 não tem `## Resolução`** — usa `## Solução` e `## Validação`. A skill que busca a
   decisão de um ticket procura pelo cabeçalho canônico.
3. **Seis tickets sem linha no mapa** em "Decisões até aqui": 040, 042, 043, 045, 056 e 057.
   Destes, 043, 045 e 057 já estão `fechado`, então falham o critério de resolução explícito da
   convenção. Os vizinhos imediatos (044, 046, 047, 059-063) têm a linha — a ausência é
   irregular, não um padrão diferente.

## O que entregar

Uma varredura só, deixando o rastreador consistente com o que ele mesmo declara:

- Os seis `resolvido` viram `fechado`. Confirme antes, ticket a ticket, que o trabalho **está**
  concluído no código: se algum estiver de fato pendente, ele vira `aberto`, e o ticket registra
  qual e por quê. Não presuma que `resolvido` significava `fechado` em todos os seis.
- O 038 ganha a seção `## Resolução`, aproveitando o conteúdo que já está lá sob os outros dois
  cabeçalhos.
- Os seis ausentes ganham a linha em "Decisões até aqui", no formato que o mapa já usa
  — um item de lista com o título linkando o arquivo do ticket, travessão, e o que se
  decidiu. A linha diz a **decisão**, não o
  título repetido; leia a `## Resolução` de cada um para escrevê-la.

## Critérios de aceite

- [x] Nenhum ticket com `status` fora de `aberto`/`fechado`
- [x] Todo ticket `fechado` tem seção `## Resolução` e uma linha em "Decisões até aqui"
- [x] A consulta de fronteira do wayfinder devolve um resultado coerente com o estado real do trabalho

## Resolução

A varredura saiu, e ela foi **maior do que este ticket previa**. Os três desvios descritos
acima estão corretos, mas o levantamento que os produziu contou por amostra: varrido ticket a
ticket, o rastreador tinha 10 `fechado` sem `## Resolução` (não 1) e 17 sem linha no mapa
(não 6). O número menor teria fechado o ticket deixando a própria condição de aceite falsa,
então o escopo seguiu o critério, não a lista.

**Os seis `resolvido` viraram `fechado`** — 038, 039, 040, 041, 042 e 056. Nenhum virou
`aberto`: os seis foram conferidos contra o código, um a um, e o trabalho está lá.
`FIAPX_EXTRACAO_FALHOU_EXCHANGE_*` resolvido por expressão no `application.properties` do
`extracao` (038); `GatewaysEmMemoria.transicionar` com `buscarPorId` devolvendo cópia (039);
`BordaDoEnvioSemTransacaoTest`, `ExtracaoRapidaPelaBordaTest` e
`ReconciliacaoAposPublicacaoInterrompidaTest` (040); `Files.createTempDirectory` no
`EspacoDeTrabalhoAdapter` com `limpar(Path)` no gateway (041); as duas `BordaDeMensageria` no
classpath de teste dos dois workers (042); `docker/rabbitmq/definitions.json` e
`scripts/primeiro-boot-roteamento.sh` (056).

**Dez tickets ganharam `## Resolução`.** Em sete foi só o cabeçalho canônico sobre conteúdo que
já estava escrito: 023 e 024 tinham `## Resolucao` sem acento; 033 tinha `## Decisão`, 034 `##
Como ficou`, 036 `## Resultado`, 037 `## Diagnóstico e correção`, e o 038 `## Solução` mais `##
Validação` — este último virou `## Resolução` com `### Validação` embaixo, a forma que os
vizinhos já usavam. Em três — 029, 031 e 032, tickets de decisão cuja resolução estava
espalhada pelo corpo — a seção foi escrita a partir do que o código mostra hoje, e não do que o
ticket planejava.

**Dezoito tickets ganharam linha em "Decisões até aqui"** — os 17 acima mais este, que fecha no
mesmo commit. Sete delas já existiam como texto,
mas na seção errada: 031, 032, 033, 035, 038, 039 e 041 estavam listados em "Ainda não
especificado", isto é, a fronteira anunciava como pergunta aberta um trabalho que já tinha
fechado. Foram movidos e reescritos no formato da seção — título linkando o arquivo,
travessão, e a decisão em vez do título repetido. As outras onze foram escritas do zero, lendo a
`## Resolução` de cada uma: 040, 042, 043, 045, 056, 057, 065, 069, 071, 072 e 074.

O comentário de contexto que sobrou em "Ainda não especificado" dizia que três daqueles tickets
"continuam abaixo" e nomeava 031, 032 e 033; foi corrigido junto, porque descrevia uma fronteira
que não existe mais.

### O caso do 074

É o único `fechado` cuja decisão pertence a **Fora de escopo** — ele foi quem pôs o ferramental
de agente para fora. A linha de lá ficou onde está e ganhou uma companheira em "Decisões até
aqui", que registra a execução e aponta para ela. A alternativa seria dar-lhe
`label: wayfinder:fora-de-escopo`, mas o ticket não é fora de escopo: ele *decidiu* que outra
coisa era.

### Validação

Auditoria mecânica dos 74 tickets contra as três condições de aceite, antes e depois:

- Valores de `status` presentes no rastreador: `aberto` e `fechado`, mais nenhum.
- Todo `fechado` tem `## Resolução` **e** uma linha própria em "Decisões até aqui" — 73 tickets,
  73 linhas, sem duplicata, contando este, que fecha no mesmo commit. A consulta casa
  `^- \[título\](tickets/NNN-`, a forma que a seção usa, e por isso não conta menção em prosa
  dentro da entrada de outro ticket como se fosse entrada própria (era assim que o levantamento
  original perdia o 035).
- Fronteira: sobra o [073](073-agents-md-descreve-conservacao-de-um-estado-que-passou.md),
  `aberto`, sem `assignee` e sem bloqueio — coerente com o estado real do trabalho.
- Todos os links `tickets/*.md` do mapa e os links novos dos tickets 029, 031 e 032 resolvem
  para arquivos existentes.

Nenhum arquivo de código, build ou teste foi tocado — o diff é `docs/wayfinder/` inteiro —,
então `./mvnw verify` não foi executado.
