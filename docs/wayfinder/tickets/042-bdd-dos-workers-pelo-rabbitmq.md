# Exercitar os workers pelo RabbitMQ nos cenários BDD

- id: 042
- label: wayfinder:bug
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `3a3ec95...f6baade`, convertido em ticket com
aprovação do usuário. Os cenários BDD de `extracao` e `notificacao` chamam controllers
diretamente. Os tickets 014 e 015 registraram essa escolha, mas a regra vigente exige
comportamento observável pela borda e não explicita uma adaptação para workers sem HTTP.
Assim, os cenários podem passar mesmo quando a entrada real por mensageria está quebrada.

## O que entregar

Os cenários de aceite dos workers devem enviar mensagens pela entrada real do RabbitMQ e
observar os resultados externos: eventos e Pacote da Extração, e e-mail da Notificação.
A regra BDD deve explicar como workers sem HTTP cumprem o princípio de teste pela borda,
mantendo Gherkin em português e sem criar endpoints apenas para testes.

## Condições de aceite

- [x] Os cenários principais dos dois workers publicam mensagens conforme o contrato,
  exercitando roteamento, desserialização e consumidores reais.
- [x] Os steps não chamam controllers, use cases ou gateways para disparar o comportamento
  que estão validando.
- [x] Os cenários de Extração verificam os eventos e o Pacote do caminho de sucesso e o
  evento esperado no caminho de falha permanente.
- [x] Os cenários de Notificação verificam o e-mail emitido a partir de `VideoFalhou`,
  incluindo destinatário e conteúdo esperado, sem expor detalhe técnico ao usuário.
- [x] A regra BDD distingue a borda HTTP do `videos` da borda RabbitMQ dos workers e
  mantém os testes integrados no surefire, com infraestrutura real de teste.
- [x] Demonstrar que uma quebra deliberada na entrada de mensageria faz o cenário
  correspondente reprovar, restaurar a configuração e executar a suíte na raiz e o smoke.

## Dependências

Nenhuma. Pode começar imediatamente; não é pré-requisito dos tickets 040 e 041.

## Resolução

Os dois workers passaram a ser exercitados pela borda que têm de verdade: o RabbitMQ. Cada
módulo ganhou uma `BordaDeMensageria` no pacote `bdd` do classpath de teste — cliente AMQP
puro (`com.rabbitmq.client`), no papel que o RestAssured cumpre nos steps do `videos`. Ela
publica na routing key real do contrato, então roteamento, `JsonObjectPayloadConverter` e
consumidor de verdade rodam em todo cenário. Nenhum step toca controller, use case ou
gateway para disparar o comportamento sob teste.

- **Extração** (`extracao.feature`): o comando `ExtrairVideo` entra por
  `fiapx.comandos`/`extracao.extrair`. O caminho de sucesso verifica `extracao.iniciada`,
  `extracao.concluida` — incluindo `chavePacote`, `quantidadeFrames` e `tamanhoBytes` — e o
  Pacote no bucket de destino. O caminho de falha permanente verifica `extracao.iniciada`,
  `extracao.falhou` com `ARQUIVO_INVALIDO` e a ausência do Pacote. Os eventos são observados
  por uma fila própria do teste (`bdd.extracao-eventos`) ligada a `fiapx.eventos` na chave
  `extracao.*`: é o análogo do cliente HTTP no lado da saída — em produção quem escuta ali é
  o `videos`. Ela é exclusiva e auto-delete porque o RabbitMQ 4.x barra fila transiente não
  exclusiva (`transient_nonexcl_queues` deprecado); a primeira versão, transiente comum,
  levou 541 INTERNAL_ERROR no `queue.declare`.
- **Notificação** (`notificacao.feature`): o evento `VideoFalhou` entra por
  `fiapx.eventos`/`video.falhou` e o e-mail sai pelo `MockMailbox` — destinatário, assunto
  com o nome do arquivo e a frase traduzida do `MotivoFalha`. Cada cenário ganhou um step
  novo, `o e-mail não expõe o código técnico`, que cobra a parte do aceite sobre não vazar
  detalhe técnico: nem o assunto nem o corpo podem conter o `codigoMotivo` do contrato.
- **Corrida de boot, fechada:** antes de publicar, o step espera a fila do worker existir
  (`queueDeclarePassive` num canal descartável, porque o broker fecha o canal ao recusar a
  pergunta). Um exchange `topic` sem binding descarta a mensagem em silêncio, e sem essa
  espera o cenário reprovaria por máquina lenta em vez de por defeito.
- **Estímulo dentro do contrato:** o cenário do motivo desconhecido publicava
  `CODIGO_DE_UM_EXTRACAO_MAIS_NOVO`, uma string que o `videos` nunca põe no fio — ele pousa
  o código não reconhecido no próprio enum e publica `motivo.name()`
  (`RabbitNotificacaoSender`, e `docs/contratos/mensagens.md` § Códigos de motivo). O
  cenário passou a publicar `DESCONHECIDO`, que é o que o `notificacao` de fato recebe; a
  tolerância a uma string fora do enum continua onde sempre esteve, no `MotivoFalhaTest`.
- **Contrato atualizado:** `docs/contratos/mensagens.md` § Quem declara o quê ganhou o
  terceiro declarante — a `BordaDeMensageria`, que só existe no classpath de teste. O
  arquivo se apresenta como fonte única da topologia e não podia calar que alguém mais
  declara exchange e fila.
- **Regra atualizada:** `AGENTS.md` § BDD passou a distinguir as duas bordas numa tabela por
  serviço, a dizer que borda não é sinônimo de HTTP e a proibir explicitamente endpoint
  criado só para teste. O javadoc do `JsonObjectPayloadConverter` dos dois workers dizia que
  "nenhum teste publica mensagem de verdade pela fila" — deixou de ser verdade e foi
  corrigido nas duas cópias.

### Validação

- Mutação deliberada, uma de cada vez, com restauração depois:
  `mp.messaging.incoming.extrair-video.routing-keys=extracao.quebrado` faz os dois cenários
  do `extracao` reprovarem em `o evento extracao.iniciada nao chegou em PT1M`;
  `mp.messaging.incoming.video-falhou.routing-keys=video.quebrado` faz os dois do
  `notificacao` reprovarem em `nenhum e-mail chegou a ... em PT30S`. Com a configuração
  antiga — steps chamando o controller — as duas quebras passariam despercebidas, que é o
  defeito que este ticket existe para remover.
- Mutação deliberada do `JsonObjectPayloadConverter`: removida a classe do `notificacao`, os
  dois cenários reprovam e o log mostra o `ClassCastException` de `JsonObject` para
  `VideoFalhou` no `VideoFalhouConsumer` — a prova de que os cenários exercitam mesmo a
  desserialização, e não só o roteamento. Classe restaurada em seguida.
- Suíte completa na raiz: `./mvnw test -Dquarkus.http.test-port=0`, 401 testes (114 videos,
  263 extracao, 24 notificacao), zero falhas, erros ou skips. A porta dinâmica é ambiental,
  o mesmo motivo dos tickets 039 e 040: o Keycloak do Compose de pé ocupa a 8081 do
  `@QuarkusTest`.
- `scripts/smoke.sh`: uma execução limpa de ponta a ponta, os nove passos verdes, depois de
  corrigido o ambiente. Vídeo válido `CONCLUIDO`, Pacote íntegro de 3 frames, falha
  permanente com `ARQUIVO_INVALIDO`, 409 em problem+json, e-mail no MailHog e 404 para o
  dono errado. Duas falhas ambientais no caminho, nenhuma do código: sobras do overlay de
  carga ocupando a 8080, e a rede do Compose corrompida por um `up` que falhou no meio
  (`failed to set up container networking`), deixando o `videos` sem resolver `keycloak` e
  `postgres`. `docker compose down` seguido de novo `up` resolveu as duas.

### Achados da revisão, acolhidos e recusados

A revisão de dois eixos sobre `cd5ee53` rodou depois da primeira versão e mudou o
resultado. Acolhidos: o estímulo off-contract do motivo desconhecido, o assunto que faltava
no segundo cenário da Notificação, `iniciadaEm` e `detalheTecnico` que os cenários da
Extração não conferiam, o `NullPointerException` mudo em campo ausente do evento (agora
reprova dizendo qual campo faltou), o `dormir` duplicado dentro do módulo `notificacao`, a
lacuna do `mensagens.md` sobre quem declara a fila observadora, e a afirmação sobre
cobertura do converter, que virou mutação medida em vez de asserção de texto.

Recusados, com motivo:

- **Editar `src/main` seria escopo esticado.** O javadoc dos dois `JsonObjectPayloadConverter`
  afirmava que "nenhum teste publica mensagem de verdade pela fila". Esta mudança tornou a
  frase falsa; deixá-la seria plantar uma armadilha para o próximo leitor. Comentário que
  mente é defeito, e corrigi-lo é parte do trabalho que o tornou mentira.
- **`AGENTS.md` ficou com trivia de implementação.** A corrida de boot e a fila exclusiva
  não são diário de depuração: são as duas coisas que o próximo autor de step vai errar se
  ninguém contar. O arquivo diz de si mesmo que "carrega o que ele não consegue dizer" pelo
  código, e é exatamente esse o caso.
- **`assertEquals(1, ...)` no e-mail nunca pega duplicata.** Verdade, e é assim de
  propósito: a unicidade do e-mail é da transição de estado do `videos` (ADR 0001), não do
  `notificacao`. Nenhum cenário aqui afirma provar unicidade.
- **`publicarVideoFalhou` tem seis parâmetros que já são um record.** O JSON é montado à mão
  de propósito: é o que o `videos` põe no fio. Usar o record do próprio `notificacao` faria
  o teste concordar consigo mesmo — um campo renomeado nos dois lados passaria batido.
