# Nada impede um canal de saída novo de nascer sem publish-confirms

- id: 034
- label: wayfinder:task
- status: fechado
- assignee: vandrep
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

## Como ficou

Quinta regra no `ArchitectureConstraintsTest`, das três opções abertas acima. O mecanismo do
primeiro item — ler os `.properties` — dentro do arquivo do segundo, porque o teste arquitetural
já é *o* lugar onde as regras deste repositório moram (AGENTS.md § *Onde a verdade mora*), e
porque cada cópia roda com o CWD no basedir do próprio módulo: o mesmo código, byte a byte
idêntico nos três, cobre os três `application.properties` sem nenhuma cópia enxergar o diretório
do vizinho. É o mesmo pressuposto que `MAIN_SOURCES` e `MODULO_DO_SERVICO` já faziam. Validação
em boot foi descartada: avisa mais tarde e custa mais para testar, e o defeito é de configuração
escrita, não de estado de runtime.

`canalDeSaidaRabbitmqDeveDeclararPublishConfirms` cobra `publish-confirms=true` de todo canal
`mp.messaging.outgoing.*` do arquivo, e não só dos que trazem `connector=smallrye-rabbitmq`
escrito. Ficou mais largo que a redação acima por causa de um furo achado na revisão: com um
conector só no classpath — o caso dos três serviços — o Quarkus liga ao RabbitMQ o canal que não
declara `connector` nenhum, então cobrar só a linha `connector=` deixaria passar exatamente o
"canal de saída novo" do título. Quem declara `connector` de outro tipo (`smallrye-in-memory`,
por exemplo) sai da regra.

Três outras saídas silenciosas foram fechadas junto, todas achadas na mesma revisão: separador
`:` ou espaço (o `.properties` aceita os três, não só `=`), nome de canal com ponto, e
`publish-confirms=false` explícito num perfil sobre um `true` sem perfil — a comparação é pelo
valor efetivo, chave de perfil vencendo a chave sem perfil, como no MicroProfile Config. Serviço
sem canal de saída — o `notificacao` de hoje — não casa nenhuma linha e passa sem asserção de
não-vazio, de propósito: a regra fica de pé para o dia em que ele publicar, que é o que o corpo
do ticket pediu.

## Limite conhecido

A regra lê `application.properties`. Canal ou override que chegue por variável de ambiente
(`MP_MESSAGING_OUTGOING_*`) passa por fora — o `docker-compose.carga.yml` já usa esse caminho
para quebrar de propósito o `extracao-falhou` no modo `mata-publicacao` do 029. Fica como está:
override de Compose é deliberado e revisado junto do arquivo que o declara, e o defeito que este
ticket persegue é o canal esquecido no `.properties`, não o canal ajustado de propósito no
deploy.

## Evidência

Vermelho, apagando `mp.messaging.outgoing.extracao-falhou.publish-confirms=true` do `extracao`
e rodando `./mvnw -pl extracao test -Dtest=ArchitectureConstraintsTest`:

```
ArchitectureConstraintsTest.canalDeSaidaRabbitmqDeveDeclararPublishConfirms
Violacoes arquiteturais encontradas:
- extracao/src/main/resources/application.properties: canal de saida
  mp.messaging.outgoing.extracao-falhou publica em RabbitMQ sem
  mp.messaging.outgoing.extracao-falhou.publish-confirms=true; sem confirms a publicacao
  recusada pelo broker completa como sucesso e a mensagem some em silencio
```

Serviço, arquivo e canal na mensagem — a condição de aceite pedia arquivo e canal, e o nome do
módulo entrou porque o caminho é relativo ao basedir e sairia idêntico nos três.

Cada uma das quatro saídas silenciosas foi provada à mão no `extracao`, acrescentando a linha ao
`application.properties` e rodando só este teste: canal sem `connector`, canal com ponto no nome,
`connector:smallrye-rabbitmq` com dois-pontos, e `%prod....publish-confirms=false` sobre um `true`
sem perfil. Os quatro reprovam, e o quarto nomeia `%prod.mp.messaging.outgoing.<canal>` — o
prefixo exato da chave que falta. Um canal `connector=smallrye-in-memory` foi acrescentado no
mesmo formato e **não** reprova: a regra não cobra quem não publica em RabbitMQ.

Verde com o arquivo restaurado: 11 testes, 0 falhas, nos três módulos, e
`scripts/verifica-testes-arquiteturais.sh` aprovando as três cópias.
