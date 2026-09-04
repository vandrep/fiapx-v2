# Os Dev Services ainda não foram provados no devcontainer rootless

- id: 036
- label: wayfinder:task
- status: fechado
- assignee:
- bloqueado-por:

## Question

O `./mvnw test` executado dentro do devcontainer não alcança os containers que o próprio
Testcontainers publica. O Docker CLI funciona pelo socket montado do daemon rootless, mas o
Testcontainers identifica `172.17.0.1` como endereço do host: Postgres, LocalStack e Keycloak
sobem internamente e recusam conexão pela porta publicada. O Ryuk apresentava ainda um segundo
sintoma: tentava usar como caminho do host o socket visto dentro do devcontainer e desaparecia
durante o startup.

A configuração já foi corrigida para colocar o devcontainer na rede do host, anunciar o
loopback como endereço do Testcontainers e informar ao Ryuk o caminho real do socket rootless.
Essa alteração, porém, só entra em vigor depois de reconstruir o devcontainer. A sessão em que
ela foi escrita continuou na bridge `172.17.0.0/16`, sem as variáveis novas, e portanto não
produziu a prova verde.

Sem essa prova, os testes `@QuarkusTest` que dependem dos Dev Services continuam sem resultado
local confiável. Isso inclui justamente o teste do adapter alterado no ticket 033: a compilação
e os testes unitários passaram, mas a execução contra Postgres foi interrompida pela
infraestrutura antes de alcançar o código.

## Evidência já obtida

Um servidor HTTP descartável publicado pelo mesmo daemon rootless foi usado como repro mínimo:

- da bridge do devcontainer, tanto `172.17.0.1` quanto o loopback recusaram a conexão;
- de um container usando rede do host, o mesmo endpoint respondeu imediatamente;
- ao informar separadamente o caminho real do socket rootless, o Ryuk permaneceu em execução,
  mas o cliente ainda não o alcançou porque a sessão antiga continuava na bridge.

Isso separa as duas responsabilidades da correção: o caminho do socket permite ao Ryuk falar
com o daemon; a rede do host e o override de endereço permitem à JVM falar com as portas que o
daemon publica.

## Condição de aceite

- [x] Reconstruir o devcontainer e comprovar que ele recebeu a rede e as variáveis configuradas.
- [x] Repetir o repro mínimo e demonstrar que uma porta publicada pelo daemon rootless é
      alcançável de dentro do devcontainer.
- [x] Executar o teste do adapter do `videos` contra os Dev Services, sem falha de startup de
      Ryuk, Postgres, RabbitMQ, LocalStack ou Keycloak.
- [x] Executar `./mvnw test` a partir da raiz e registrar o resultado; nenhuma suíte pode parar
      por endereço ou socket incorreto dos Dev Services.
- [x] Decidir e documentar se o suporte fica restrito a host Linux com UID 1000 ou se o caminho
      do socket rootless será parametrizado para outros usuários.

## Fora de escopo

Corrigir falhas funcionais que a suíte revele depois que os Dev Services estiverem acessíveis.
Elas ganham tickets próprios: este termina quando o ambiente deixa os testes chegarem ao código.

## Resultado

O devcontainer foi reconstruído em 2026-09-04. O comando efetivo trouxe `--network=host`,
`TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1` e
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/user/1000/docker.sock`; o último valor veio da
expansão de `${localEnv:XDG_RUNTIME_DIR}`, não de um UID gravado na configuração. O Docker
acessado pelo container confirmou `rootless` nas opções de segurança.

No repro mínimo, um `nginx:alpine` descartável publicado pelo mesmo daemon em
`127.0.0.1:32832` respondeu de dentro do devcontainer com `Welcome to nginx!`.

`./mvnw -pl videos -Dtest=VideoDataSourceAdapterTest test` terminou com código 0. Os logs
registraram o Ryuk em execução e Postgres, RabbitMQ, LocalStack e Keycloak iniciados; todos os
endereços entregues à aplicação usaram `127.0.0.1`.

`./mvnw test`, executado da raiz, iniciou os Dev Services de `videos` e `extracao` sem erro de
socket ou endereço e chegou aos testes funcionais. Terminou com código 1 antes de entrar em
`notificacao` porque
`ExtracaoEstacionamentoTest.publicacaoFalhaNoConsumoDaDlqChegaAoEstacionamento` não encontrou a
mensagem após 45 segundos (`53` testes do módulo, `1` falha). O teste isolado repetiu a mesma
falha. `./mvnw -pl notificacao test` foi executado em seguida, iniciou Ryuk e RabbitMQ por
`127.0.0.1` e terminou com código 0. Assim, nenhuma das três suítes parou por infraestrutura.
O vermelho da raiz não reabre a infraestrutura comprovada aqui; é o defeito funcional separado
no [ticket 037](037-estacionamento-nao-recebe-falha-da-dlq.md).

A imagem ganhou `ffmpeg` e `ffprobe`, pré-requisitos já declarados pelo `AGENTS.md`, para que a
suíte do `extracao` pudesse de fato chegar ao código. O suporte fica restrito a Docker rootless
em host Linux com `XDG_RUNTIME_DIR` definido, mas não ao UID 1000. O requisito e o rebuild
necessário estão documentados no `README.md`.
