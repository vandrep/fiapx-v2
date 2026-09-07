# A cauda de ack/nack repetida em três consumidores

- id: 067
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7` (Duplicated Code), convertido em
ticket com aprovação do usuário.

## O problema

O `ExtracaoEventosConsumer` do `videos` já extraiu a cauda de ack manual num `comAckManual`.
`ExtrairVideoConsumer` e `ExtracaoDlqConsumer` (no `extracao`) e `VideoFalhouConsumer` (no
`notificacao`) repetem a mesma expressão inline, cada um no seu arquivo.

A duplicação importa mais aqui do que a contagem de linhas sugere: quem erra a cauda de
ack/nack não quebra o build nem reprova cenário — a mensagem some ou reentrega para sempre, que
é a família de defeito que os tickets 034, 037 e 040 já perseguiram uma vez cada. Uma forma só,
nomeada, é o que faz o próximo consumidor nascer certo.

## O que entregar

Os três consumidores restantes passando pela mesma forma que o `ExtracaoEventosConsumer` usa.

**O módulo compartilhado está fora de questão** — o mapa o descarta duas vezes, nas Notas
("Maven multi-módulo com parent agregador, **sem** módulo `shared`") e em Fora de escopo, e
`docs/arquitetura.md` dá o motivo: duplicar é mais honesto que acoplar três serviços por um
jar. A forma se repete por serviço, como o `Rastro` e o `JsonObjectPayloadConverter` já se
repetem. O que este ticket unifica é a expressão dentro de
cada serviço, não entre eles. O [ticket 070](070-duplicacao-entre-modulos-nao-registrada.md)
é quem trata a duplicação entre módulos.

Cuidado com o `ExtracaoDlqConsumer`: ele é o fim da linha do
[ADR 0001](../../adr/0001-politica-de-falhas.md), e nack ali não reentrega — o comportamento
tem que sair idêntico, não "equivalente".

## Critérios de aceite

- [ ] Os quatro consumidores usam uma forma nomeada para a cauda de ack, uma por serviço
- [ ] Nenhum módulo novo, nenhuma dependência entre serviços
- [ ] `./mvnw test` verde a partir da raiz, com os cenários BDD dos dois workers passando pelo RabbitMQ real
