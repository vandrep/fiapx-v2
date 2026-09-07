# Garantir o roteamento das mensagens no primeiro boot

- id: 056
- label: ready-for-agent
- status: fechado
- assignee: Codex
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `3a3ec95...528bc3a`, convertido em ticket com
aprovação do usuário. Na primeira subida do Compose, a borda pública pode aceitar um
Vídeo antes de o `extracao` declarar sua fila e binding. As definições do broker não
provisionam essa topologia e a disponibilidade do `videos` não depende dela. Um comando
confirmado pelo exchange sem destino pode receber a marca de publicação e ficar fora da
reconciliação, deixando o Vídeo em `RECEBIDO`. A mesma janela existe para `VideoFalhou`
antes da inicialização do `notificacao`.

Achado por inspeção estática, ainda não reproduzido. O ticket 042 reconhece a perda de
mensagem em exchange sem binding e evita a corrida nos testes; falta verificar e fechar
a janela na orquestração real. O enunciado exige não perder requisições; os ADRs 0001 e
0003 definem as garantias de publicação e recuperação que esta mudança deve preservar.

## O que entregar

Um Vídeo aceito durante a primeira inicialização do sistema continua até um desfecho,
mesmo que os workers iniciem depois da borda pública. Quando houver falha definitiva,
a notificação chega ao Dono. A disponibilidade para publicação deve garantir o roteamento
necessário, por provisionamento prévio da topologia ou outra solução comprovada.

## Condições de aceite

- [x] Reproduzir a janela em ambiente isolado com broker sem topologia anterior e
  inicialização deliberadamente atrasada dos consumidores, registrando o resultado antes
  da correção sem apagar volumes de trabalho existentes.
- [x] Garantir que publicações não sejam consideradas entregues com sucesso quando os
  bindings necessários ainda não existem, cobrindo comandos e eventos do fluxo completo.
- [x] Enviar um Vídeo válido durante a inicialização atrasada e verificar pela borda
  pública que todo envio aceito chega a `CONCLUIDO` e permite baixar um Pacote íntegro.
- [x] Exercitar um Vídeo que falha definitivamente com o `notificacao` atrasado e verificar
  o estado `FALHOU` pela borda pública e o e-mail recebido pelo Dono.
- [x] Preservar filas duráveis, publisher confirms, tolerância a duplicatas e reconciliação,
  sem introduzir dependências circulares de inicialização entre os serviços.
- [x] Tornar a regressão reexecutável e validar também a recriação da stack com dados
  persistidos, sem perda de mensagens já enfileiradas.
- [x] Executar a suíte a partir da raiz, o smoke e a prova de concorrência caso a solução
  altere o canal de entrada ou as réplicas do `extracao`; registrar resultados e limitações.

## Dependências

Nenhuma. Pode começar imediatamente e não depende do ticket 057.

## Resolução

O Compose agora importa no RabbitMQ a topologia durável completa antes de iniciar os
serviços de negócio: exchanges `fiapx.comandos`/`fiapx.eventos`, exchanges de dead-letter,
filas de trabalho quorum, DLQs com os tipos já definidos pelo contrato, argumentos de
entrega e todos os bindings do contrato. As propriedades dos
serviços continuam declarando a mesma topologia, de forma idempotente, porque os Dev
Services dos testes sobem um broker limpo e não leem o arquivo do Compose.

O ensaio reexecutável está em [`scripts/primeiro-boot-roteamento.sh`](../../../scripts/primeiro-boot-roteamento.sh).
Ele usa um projeto e volumes próprios (o nome pode ser trocado por
`FIAPX_PRIMEIRO_BOOT_PROJETO`), sobe o broker e o `videos` com `extracao` e
`notificacao` desligados, comprova via API de management que os destinos e bindings já
existem, envia um Vídeo pela API pública e só então libera os workers. O script espera o
Vídeo chegar a `CONCLUIDO` e deixa a stack para inspeção; nunca usa `down -v`.

A reprodução anterior foi estabelecida por inspeção da ordem de inicialização: com o
broker limpo, os exchanges eram criados pelo primeiro serviço que declarasse um canal, e
`videos` dependia somente da saúde do RabbitMQ. Assim, uma publicação confirmada no
exchange sem binding podia completar sem entregar a mensagem. A correção elimina essa
janela no primeiro boot; `publish-confirms`, filas duráveis, tolerância a duplicatas e a
reconciliação permanecem inalterados. A prova de falha definitiva e do e-mail continua no
`smoke.sh`, que é executado depois do ensaio quando a stack está disponível.

### Validação

- `./mvnw package -DskipTests` e `./mvnw test -Dquarkus.http.test-port=0` passaram na raiz:
  132 testes em `videos`, 269 em `extracao` e 24 em `notificacao`, sem falhas, erros ou
  skips. A suíte também confirmou as três cópias idênticas do teste arquitetural.
- `FIAPX_PRIMEIRO_BOOT_PROJETO=fiapx-primeiro-boot-2 ./scripts/primeiro-boot-roteamento.sh`
  passou com um Vídeo aceito antes dos workers chegando a `CONCLUIDO` e um arquivo inválido
  chegando a `FALHOU` com `notificacao` parada; depois da retomada, o e-mail correspondente
  foi recebido. Os volumes dos ensaios ficaram preservados.
- `scripts/smoke.sh` passou na stack padrão com volumes persistidos: Pacote ZIP íntegro,
  falha `ARQUIVO_INVALIDO`, resposta `409`, e-mail correlacionado e isolamento por dono.
- `scripts/concorrencia.sh 8` passou com pico de dois Videos em `PROCESSANDO` por duas
  amostras, oito `CONCLUIDO` e oito Pacotes íntegros.
- `jq empty docker/rabbitmq/definitions.json`, `bash -n scripts/primeiro-boot-roteamento.sh`,
  `docker compose config --quiet` e `git diff --check` passaram.
