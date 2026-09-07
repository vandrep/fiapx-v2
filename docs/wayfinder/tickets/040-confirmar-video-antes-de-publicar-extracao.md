# Confirmar a persistência do Vídeo antes de publicar a Extração

- id: 040
- label: wayfinder:bug
- status: fechado
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem e evidência

Achado do eixo Spec da revisão de `3a3ec95...f6baade`, convertido em ticket com aprovação
do usuário. A corrida foi identificada pela leitura do fluxo e ainda precisa de reprodução
controlada: a transação do envio engloba a persistência, a publicação de `ExtrairVideo` e a
marca de publicação. Os consumidores de eventos confirmam a mensagem quando não encontram
o Vídeo. Se a Extração terminar antes do commit, seus eventos podem ser descartados e o
envio depois confirmar um Vídeo em `RECEBIDO` com a marca preenchida, fora da reconciliação.

## O que entregar

Um Vídeo aceito deve estar persistido e visível aos consumidores antes de o comando de
Extração ser publicado. Eventos rápidos precisam produzir o desfecho observável pela API,
e uma interrupção entre a persistência e a publicação deve continuar recuperável pela
reconciliação. Preservar as decisões dos ADRs 0001, 0002 e 0003 e a resposta de envio do
contrato HTTP.

## Condições de aceite

- [x] Reproduzir a corrida com sincronização controlada, sem depender de sleeps ou sorte,
  demonstrando a confirmação de evento antes de o Vídeo estar visível no banco.
- [x] Garantir commit da persistência antes da publicação do comando; apenas flush não
  satisfaz a visibilidade por outra transação.
- [x] Verificar pela borda que eventos de Extração rápida, incluindo falha permanente,
  não deixam um Vídeo aceito sem desfecho por ausência da linha no consumo.
- [x] Interromper o caminho entre commit e publicação e comprovar que a reconciliação
  recupera a pendência; falha de publicação não pode deixar marca de sucesso.
- [x] Executar a suíte a partir da raiz, o smoke e o ensaio de conservação aplicável à
  reconciliação, registrando resultados e eventuais falhas preexistentes separadamente.

## Dependências

Nenhuma. Pode começar imediatamente; os testes desta correção pertencem a este ticket.

## Resolução

O commit `7f78e45` fez `VideoDataSourceAdapter.adicionar` abrir sua própria transação, e
`VideosResource` não mantém mais uma transação que englobe a publicação do comando. Portanto, a
conclusão da persistência no encadeamento de envio significa commit, não somente flush. Esta
sessão fechou as três provas que faltavam.

- **A corrida, reproduzida** (`VideoDataSourceAdapterTest`, o par
  `envioDentroDeUmaTransacaoConfirmaOComandoSemOVideoEstarVisivel` e
  `envioSemTransacaoAmbienteConfirmaOComandoComOVideoJaVisivel`): é o `EnviarVideoUseCase` de
  verdade, com um `ExtracaoSender` no papel do consumidor que confirma o comando lendo por uma
  **conexão própria do pool** — o que outro processo enxergaria. Dentro de uma transação
  ambiente, que era o efeito do `@WithTransaction` na borda, o consumidor conta zero linhas;
  sem ela, conta uma. A sincronização é o encadeamento da própria transação, que só commita
  depois da leitura: nada de sleep ou sorte. O que fica registrado é a contagem, e não um
  booleano, para que um consumidor que nunca rodou não passe por "não enxergou".
- **A cerca do defeito** (`BordaDoEnvioSemTransacaoTest`): como `Panache.withTransaction` se
  **junta** a uma transação ambiente, devolver `@WithTransaction` ao `VideosResource` reabriria
  a corrida sem que nenhum teste do adapter percebesse. Medido por mutação: com a anotação de
  volta na borda, este teste reprova; sem ela, passa.
- **O evento rápido pela borda** (`ExtracaoRapidaPelaBordaTest`): a `ExtracaoFalhou` é
  publicada no RabbitMQ real no instante em que o `ExtrairVideo` aparece no broker — ou seja,
  **dentro** do `POST /videos`, antes do 202 —, e o desfecho `FALHOU` com motivo
  `ARQUIVO_INVALIDO` é observado pelo `GET /videos/{id}`. Nenhum consumer, controller ou use
  case é chamado direto. **Limite medido e assumido:** este teste sozinho não reprova o defeito
  — com `@WithTransaction` de volta na borda ele continua verde, porque a janela entre o
  publish e o commit é curta demais para ser vencida por uma ida e volta ao broker. Ele prova o
  outro lado do aceite, a observabilidade do desfecho pela API; quem reprova o defeito são os
  dois itens acima. O javadoc do teste diz isso no lugar.
- **A janela real entre commit e publish**
  (`ReconciliacaoAposPublicacaoInterrompidaTest`): o `EnviarVideoUseCase` roda com Postgres,
  MinIO e `ReconciliacaoController` de produção, e a interrupção entra no `ExtracaoSender`,
  que é onde o envio ao broker acontece. Com a publicação interrompida, `comando_publicado_em`
  fica nula e nada chega ao exchange `fiapx.comandos` — espiado por uma fila própria ligada à
  routing key `extracao.extrair`, que é o que o `extracao` veria. Passada a folga do ADR 0003,
  a varredura real republica o `ExtrairVideo`, ele chega ao broker com a chave de destino
  correta e só então a marca passa a valer. O teste em memória continua no lugar: lá a garantia
  é do algoritmo, aqui é da infraestrutura que ele usa.

A folga de um minuto da varredura é vencida envelhecendo `recebido_em` por SQL, e não
esperando: é o equivalente determinístico da passagem do tempo.

### Fora de escopo, deliberado

A janela **oposta** do ADR 0003 — publish bem-sucedido e marca perdida — continua provada só
em memória (`ReconciliarPublicacoesPendentesUseCaseTest`). O aceite 4 fala da janela entre o
commit e a publicação, e foi essa que ganhou prova real. O cenário de borda também não virou
`.feature`: o que ele exige é responder ao comando no instante em que ele chega ao broker, o
que não se expressa como step Gherkin sem duplicar a cola do Cucumber.

### Validação

- Suíte completa na raiz: `./mvnw test -Dquarkus.http.test-port=0`, 401 testes (114 videos,
  263 extracao, 24 notificacao), zero falhas, erros ou skips. A porta dinâmica é ambiental —
  como no ticket 039, o Keycloak do Compose de pé ocupa a 8081 do `@QuarkusTest`, e com a
  porta padrão o boot do `videos` falha com `Port already bound: 8081`.
- Mutação deliberada, com `@WithTransaction` de volta em `VideosResource.enviar`:
  `BordaDoEnvioSemTransacaoTest` reprova; `ExtracaoRapidaPelaBordaTest` **não** reprova, e é
  esse resultado que fixou a divisão de papéis registrada acima.
- `scripts/smoke.sh` integral: Vídeo válido `CONCLUIDO`, Pacote íntegro de 3 frames, falha
  permanente com `ARQUIVO_INVALIDO`, 409 em problem+json, e-mail no MailHog e 404 para o dono
  errado.
- `scripts/carga/conservacao.sh mata-videos`: a primeira rodada saiu **inválida** pelo próprio
  portão do script (nenhum 202 antes da queda, com o kill em 3 s). Com `FIAPX_ATRASO_KILL=15`
  a rodada valeu e passou nos cinco critérios: 387 aceitos, 387 terminais em 5 s, zero presos,
  amostra de 10 ids conferida pela API e zero `FALHOU`. O `jq: parse error` que a sessão
  anterior viu no critério de amostra não reapareceu.
