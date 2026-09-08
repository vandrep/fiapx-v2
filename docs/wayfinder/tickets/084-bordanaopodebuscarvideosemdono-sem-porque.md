# `bordaNaoPodeBuscarVideoSemDono` não tem `porquê` em lugar nenhum

- id: 084
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Spec da revisão do trabalho do [ticket 078](078-regras-do-teste-arquitetural-sem-linha-no-agents.md),
convertido em ticket.

## O problema

O `ArchitectureConstraintsTest` tem a regra `bordaNaoPodeBuscarVideoSemDono`: `Resource` e
`Controller` não podem chamar `.buscarPorId(`, só `.buscarPorIdEDono(`. A mensagem de violação
diz a troca ("use buscarPorIdEDono") mas não diz por quê.

Ao contrário de `processoExternoSoDeveApareceEmFramework`, que tem javadoc citando os tickets
006 e 015 e o motivo, `bordaNaoPodeBuscarVideoSemDono` não tem comentário nem javadoc — nem no
teste, nem no `AGENTS.md`. O 078 considerou incluí-la na série narrada em
§ *As três cópias do teste arquitetural*, mas decidiu que documentar essa regra é trabalho
próprio, fora do escopo do que aquele ticket pediu (a sétima e a oitava entrada, especificamente).

## O que entregar

Uma entrada na série do § *As três cópias do teste arquitetural* — no mesmo formato das outras,
o que a regra cobra e por que existe — ou, se o porquê estiver noutro lugar do repositório
(commit, ADR, outro ticket), a linha bastando apontar para lá em vez de reconstruí-lo.

## Critérios de aceite

- [x] `bordaNaoPodeBuscarVideoSemDono` tem uma linha no `AGENTS.md`, ou uma referência a onde o
  porquê já está registrado
- [x] A entrada explica por que buscar `Video` sem checar dono é o defeito que a regra evita,
  não só o que ela cobra

## Resolução

**Implementado como entrada nova na série do § *As três cópias do teste arquitetural***, no
`AGENTS.md`, no mesmo formato das outras oito.

O *porquê* **não** precisou ser reconstruído: ele já existia no repositório, partido em dois
lugares que nunca se encontravam. `VideoGateway` traz o *o quê* e cita o ticket de origem — "a
borda HTTP sempre usa `buscarPorIdEDono`; o caminho de mensageria, que nao recebe Dono, usa
`buscarPorId`. O teste arquitetural proibe Resource e controller HTTP de usarem a busca sem
posse (ticket 031)" — e `docs/contratos/http-videos.md` traz o *por quê* sem nomear a regra: "o
dono vem do token, nunca do request", e o § *Vídeo de outro usuário*, que escolhe `404` em vez de
`403` para não confirmar a existência do id. A entrada nova junta os dois e nomeia o defeito, que
é o que faltava.

**O defeito que a regra evita é de autorização, não de camada** — e é isso que a distingue das
outras oito, todas sobre dependência entre camadas. `GET /videos/{id}` recebe um id adivinhável do
cliente e o `Dono` só do token; uma borda que buscasse por id e devolvesse o que achou entregaria
o Vídeo de outro usuário a quem digitasse o UUID certo. Nada no compilador separa as duas
chamadas: mesmo tipo de retorno, e a busca sem posse é legítima no `core`. Daí a regra ser
sintática — o erro também é.

Ficou escrito o segundo efeito, que eu não esperava encontrar e que é o mais forte dos dois: a
regra **guarda o `404` do contrato**. Filtrando por dono na consulta, "não é seu" e "não existe"
chegam ao Resource como o mesmo `Optional.empty()`, e o não-vazamento é estrutural. Com
`buscarPorId`, a borda teria o Vídeo em mãos e precisaria comparar o dono ela mesma para então
mentir — e é aí que alguém escreve `403`, ou esquece a comparação.

**Sobre o ordinal.** A regra é a nona da série mas é **mais velha que quatro das que a
precedem**: chegou no ticket 031, antes da quinta (034), da sexta (061), da sétima (064) e da
oitava (068). Renumerar a série para pôr cada uma no seu lugar cronológico invalidaria o registro
do [078](078-regras-do-teste-arquitetural-sem-linha-no-agents.md), que fala pelos ordinais ("a
sétima e a oitava entrada"). Ficou como nona, com uma linha dizendo que o ordinal é de registro e
não de chegada.

**O que este ticket não entrega**: javadoc na regra, nas três cópias do teste. Os critérios pedem
`AGENTS.md` ou referência, e a série é o lugar que o ticket nomeou. Editar as três cópias byte a
byte para repetir o que a prosa já diz gastaria a guarda do
`scripts/verifica-testes-arquiteturais.sh` sem acrescentar registro. A mensagem de violação
continua dizendo só a troca — quem quiser o motivo acha pelo nome da regra no `AGENTS.md`.

Nenhuma linha de código Java mudou, nenhum teste mudou, nenhum comportamento mudou.
