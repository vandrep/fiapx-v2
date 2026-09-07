# `AckManual` é a quinta cópia deliberada, e o registro diz quatro

- id: 077
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `08d76ed...c592711`, convertido em ticket com aprovação
do usuário.

## O problema

O [ticket 067](067-cauda-de-ack-repetida-nos-consumidores.md) criou `AckManual` nos três
serviços, em `framework/dispatcher/`. As três são idênticas exceto pela linha `package`.

Dois commits depois, o [070](070-duplicacao-entre-modulos-nao-registrada.md) escreveu o
`AGENTS.md` § *As cópias deliberadas entre serviços*, que abre assim:

> Além dos records do contrato de mensagens, **quatro** implementações se repetem entre
> serviços: `Rastro` e `JsonObjectPayloadConverter` nos três, `comRepeticao` nos três clientes
> de I/O e `MotivoFalha.doCodigo` em `videos` e `notificacao`.

`AckManual` não está na lista. A seção existe para carregar uma obrigação — "ao mudar a parte
comum de uma dessas implementações, inspecione todas as cópias" — e a obrigação não alcança o
que não está listado. É exatamente o defeito que o 070 foi aberto para prevenir, reintroduzido
pelo commit vizinho.

Há uma diferença que o ticket precisa decidir, não só registrar. A seção justifica a ausência de
guarda automática assim:

> Nenhuma delas tem identidade byte a byte como invariante, e uma comparação parcial confundiria
> diferença local legítima com esquecimento.

Para as outras quatro isso é verdade — o `Rastro` do `videos` oferece `marcar` e os outros não.
Para `AckManual` não é: as três cópias **são** idênticas fora do `package`, e nada no desenho
sugere que devam divergir. Ou ela é a segunda exceção explícita, com guarda no agregador junto
do `ArchitectureConstraintsTest`, ou o texto passa a dizer por que identidade byte a byte aqui
não é invariante.

## O que entregar

- `AGENTS.md` § *As cópias deliberadas entre serviços* cobrindo as cinco famílias.
- A decisão sobre guarda para `AckManual`, escrita: exceção com verificação automática, como as
  três cópias do teste arquitetural, ou família sem guarda como as outras quatro — e o motivo.
- Se a decisão for guardar, a verificação entra em `scripts/verifica-testes-arquiteturais.sh` ou
  ao lado dele, presa ao agregador. `mvn -f <servico>/pom.xml` pula guarda de agregador em
  silêncio, e é o que o `AGENTS.md` já registra.

## Critérios de aceite

- [ ] A seção não diz mais "quatro" quando são cinco
- [ ] A decisão sobre guarda está escrita, com o motivo, seja qual for o lado
- [ ] Se houver guarda, ela reprova o build quando uma das três cópias diverge
- [ ] `./mvnw test` verde a partir da raiz
