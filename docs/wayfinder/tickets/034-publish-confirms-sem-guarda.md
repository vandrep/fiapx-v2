# Nada impede um canal de saída novo de nascer sem publish-confirms

- id: 034
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por:

## Question

O [029](029-terminal-na-dlq-do-extracao.md) ligou `publish-confirms=true` nos três canais de
saída do `extracao`, que estavam sem — o mesmo defeito que o [027](027-melhorias-medidas.md)
já tinha corrigido um serviço abaixo, nos dois canais de saída do `videos`. O mesmo buraco
apareceu duas vezes, em dois serviços diferentes, e as duas vezes só foi achado por medição
ou revisão manual (a do 029 saiu de uma sessão de grilling sobre este próprio ticket). Nada
no build reprova um canal `mp.messaging.outgoing.*` novo, em qualquer um dos três serviços,
que nasça com `connector=smallrye-rabbitmq` e sem `publish-confirms=true` — o defeito é
silencioso por definição (`docs/contratos/mensagens.md`, `ExtracaoFalhou`/`ExtracaoDlqConsumer`
já documentam o porquê), então a próxima vez ele não seria pego numa rodada de carga: seria
pego meses depois, com uma mensagem real perdida.

## O que fica decidido

Uma regra automática, não mais revisão manual: todo canal `mp.messaging.outgoing.*` com
`connector=smallrye-rabbitmq`, em qualquer um dos três `application.properties`, declara
`publish-confirms=true` no mesmo prefixo de canal. `notificacao` não publica em RabbitMQ
hoje, mas a regra vale para o serviço, não para o canal que existe — é o que a protege se
isso mudar.

O mecanismo fica em aberto para quem pegar o ticket decidir entre:

- um teste que lê os três `application.properties` (regex ou parser de `.properties`) e
  falha apontando o arquivo e o canal faltante — mais simples, não sobe Quarkus;
- uma quinta regra no `ArchitectureConstraintsTest`, se o padrão das outras regras daquele
  arquivo servir de guia melhor — nesse caso, repita a mudança nas três cópias (AGENTS.md §
  *As três cópias do teste arquitetural*, `scripts/verifica-testes-arquiteturais.sh`);
- validação em boot via config source, se preferir falhar em runtime a falhar em teste —
  mais caro de testar, mais tarde para avisar.

## Condição de aceite

Um canal de saída `smallrye-rabbitmq` sem `publish-confirms=true`, em qualquer um dos três
serviços, reprova `./mvnw test` com o arquivo e o canal faltante na mensagem — não só
reprovaria uma rodada de carga ou uma revisão manual, como aconteceu nas duas vezes
anteriores (027, 029).

## Por que ticket próprio, e não uma linha a mais no 029

O 029 fechou o buraco que existia; este ticket fecha a classe do buraco, para que a terceira
vez não precise de outra sessão de grilling para aparecer. Achado na revisão de código da
própria implementação do 029, em 2026-09-04 — não faz parte da rodada de arquitetura que
abriu os tickets 029–033 (ver `docs/wayfinder/map.md` § *Ainda não especificado*).
