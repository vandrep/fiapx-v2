# Posse do arquivo no download do Vídeo e limpeza dos parciais

- id: 102
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-13, SHA inicial 0d46527)
- bloqueado-por:
- prioridade: P2

## Origem

Revisão arquitetural e discussão com o mantenedor em 2026-09-13, sobre
`develop @ 0d46527e41dbfe58be2553d0d39841f773e421eb`. O escopo abaixo foi confirmado;
o pedido final foi criar um ticket para **outra sessão implementar**. Esta sessão não
alterou código nem testes do repositório.

## A premissa inicial foi corrigida pela medição

O javadoc de `extracao/.../framework/service/ArquivoMinioClient.java`, junto de `baixar`,
diz que um blip depois de começar a escrita deixa o destino existente e consome as
repetições com `FileAlreadyExistsException`. O javadoc de `RepeticaoNoMinioTest` registra
que o dublê falha antes de `prepare()` e não cobre esse caso.

**O comentário não é prova do defeito.** Uma sondagem isolada com o transformador real do
SDK local contradisse a generalização e revelou outro problema:

| Cenário | Resultado observado |
|---|---|
| Falha do stream após observar 6 bytes gravados | `IOException`; destino removido antes de completar a falha |
| Novo transformador para o mesmo destino, depois dessa falha | Download concluído, conteúdo exato `inicio-fim` |
| Destino criado antes de chamar o transformador | `FileAlreadyExistsException`; **arquivo preexistente apagado** |

A sondagem usou `sdk-core:2.41.18`, identificado em
`extracao/target/quarkus-app/quarkus-app-dependencies.txt`, com o classpath do artefato
local. A inspeção do bytecode mostrou `defaultCreateNew()` com `CREATE_NEW` e
`FailureBehavior.DELETE`; o tratamento de falha tenta excluir o destino também quando a
abertura falha. A exclusão não está condicionada à criação bem-sucedida pela operação.

Isto foi medido **no transformador**, sem MinIO, Quarkus ou cadeia completa de repetição.
Não prova perda de arquivo no fluxo normal de produção: `EspacoDeTrabalhoAdapter` cria um
diretório exclusivo por tentativa, atomicamente, e o adapter de MinIO escolhe o basename
da chave dentro dele. Prova que a proteção de posse não pode ser delegada cegamente ao
transformador e que falta cobertura do caminho depois da escrita.

Para reproduzir sem depender dos arquivos temporários desta sessão: usar
`AsyncResponseTransformer.toFile(destino)`, `prepare()`, `onResponse(...)` e
`onStream(SdkPublisher.adapt(SimplePublisher<ByteBuffer>))`; enviar bytes, esperar a
escrita ser observável no disco e então sinalizar `error(IOException)`. Esperar o future
falhar antes de verificar o destino e iniciar outra transferência. Repetir separadamente
com um arquivo de conteúdo conhecido criado antes de `onStream`. Usar somente arquivos
descartáveis próprios: o último cenário apaga o arquivo na versão sondada. Confirmar a
versão efetivamente resolvida na sessão de implementação, pois `target` pode estar antigo.

## Escopo confirmado

1. Cada repetição reinicia o download inteiro; não há retomada por posição.
2. Preservar a política do [ADR 0001](../../adr/0001-politica-de-falhas.md): no máximo
   três chamadas ao recurso, a primeira mais duas repetições, com Mutiny e a espera/jitter
   atuais. Repetição não é nova tentativa de Extração.
3. Um destino preexistente causa falha e permanece intacto. A operação só pode descartar
   arquivos que ela própria criou; a proteção não pode depender apenas de um teste de
   existência sujeito a corrida antes da abertura.
4. Remover os parciais próprios entre chamadas e ao esgotar as repetições. Aproveitar o
   comportamento do SDK quando suficiente; não duplicar limpeza por causa do comentário
   desmentido. Arquivo completo permanece disponível no destino após sucesso.
5. Se a limpeza necessária falhar, interromper o download, sem continuar repetindo sobre
   estado local inválido. A falha devolvida preserva tanto o erro da transferência quanto
   o da limpeza, de forma inspecionável; a representação concreta fica para implementação.
   Nesse caso não se promete remoção: a limpeza do diretório e a varredura de órfãos
   continuam como proteção complementar.
6. Manter a interface atual. A responsabilidade fica no module de download existente;
   não criar module compartilhado nem levar a gestão de parciais ao fluxo de Extração.
   A escolha do mecanismo concreto de posse fica para a implementação.

## Onde trabalhar e o que preservar

- `extracao/src/main/java/br/com/fiapx/extracao/framework/service/ArquivoMinioClient.java`:
  chamada S3, repetição e comentário que a sondagem contradisse.
- `extracao/src/main/java/br/com/fiapx/extracao/framework/service/ArquivoMinioAdapter.java`:
  destino, span e tradução de falha; preservar streaming em disco e contrato do gateway.
- `extracao/src/test/java/br/com/fiapx/extracao/framework/service/RepeticaoNoMinioTest.java`:
  exercitar a interface do cliente com transformador real e falha após escrita observada.
- `ProcessarExtracaoUseCase` e `EspacoDeTrabalhoAdapter`: ler para preservar a limpeza por
  tentativa, o isolamento entre réplicas e a recuperação dos órfãos, sem transferir essas
  responsabilidades para o cliente S3.

Não mudar upload de Pacote, SMTP, Postgres, contrato de mensagens, número de entregas da
fila ou política de ack/nack. Se tocar na parte comum de `comRepeticao`, inspecionar todas
as cópias conforme o `AGENTS.md`; a gestão de parcial é específica deste download.
Não reescrever tickets fechados para corrigir a premissa histórica. Não há termo novo de
domínio nem reversão de ADR: o vocabulário existente do `CONTEXT.md` continua suficiente.

## Critérios de aceite

- [x] Teste observa bytes em disco antes de injetar o blip e comprova recuperação com
      conteúdo completo e exato, sem prefixo duplicado nem sobra do parcial anterior.
- [x] Falhas persistentes após escrita respeitam o limite de três chamadas ao recurso;
      o desfecho é falha e não sobra parcial próprio quando a limpeza funciona.
- [x] Destino preexistente mantém seu conteúdo intacto e a operação falha; a proteção de
      posse também cobre colisão na criação, sem apagar arquivo que a operação não criou.
- [x] Falha determinística na limpeza interrompe novas chamadas de download e devolve
      os dois erros inspecionáveis. O cenário não depende de permissões que `root` ignora.
- [x] Sucesso entrega o arquivo íntegro no destino, preservando a interface e o streaming;
      operações em espaços de tentativas diferentes não interferem entre si.
- [x] Comentários do cliente e do teste descrevem o comportamento verificado, distinguindo
      a limpeza já oferecida pelo SDK da proteção adicional de posse e de falha na limpeza.
- [x] `./mvnw test` verde a partir da raiz, com Docker e `ffmpeg`/`ffprobe` disponíveis.
- [x] `scripts/carga/travamento.sh` verde contra imagens atualizadas, conforme o gatilho
      do `AGENTS.md` para mudanças nos adapters de I/O do `extracao`; registrar comando,
      configuração, quantidade de ciclos e resultado. Rodar também `scripts/smoke.sh` se
      houver mudança de imagem/configuração conforme seus gatilhos.

## Início da próxima sessão

Reivindicar este ticket antes de implementar. Trabalhar na `develop`, registrar o SHA
inicial para revisão e usar TDD conforme o mapa. Começar convertendo a sondagem em prova
repetível no teste existente; separar o comportamento que já passa da proteção de posse
que precisa ficar vermelha. Ao fechar, registrar a resolução e acrescentar a decisão ao
mapa, conforme `docs/wayfinder/TRACKER.md`.

## Resolução

Implementado em 2026-09-13 sobre `develop @ 0d46527`. A versão resolvida na sessão foi a mesma
da sondagem, `sdk-core`/`s3` **2.41.18** (`./mvnw dependency:list`, não o `target`).

### O que a sessão confirmou antes de mexer

O bytecode de `FileAsyncResponseTransformer.exceptionOccurred` fecha o canal e, com
`FailureBehavior.DELETE`, chama `Files.deleteIfExists` dentro de `FunctionalUtils.runAndLogError`.
Então, além de não olhar posse, **a exclusão do SDK não relata falha**: ela vira log, e o item 5
do escopo não tinha como ser atendido com a limpeza do SDK. Foi isso que decidiu trocar `DELETE`
por `LEAVE` em vez de acrescentar uma segunda limpeza.

A sondagem virou seis cenários em `RepeticaoNoMinioTest`, com um dublê novo que passa pelo
transformador real e só derruba o stream depois de ver os bytes no disco. Contra o código de
`0d46527`, antes da mudança:

| Cenário | Antes |
|---|---|
| blip depois da escrita observada baixa de novo, conteúdo exato | verde |
| falha persistente depois da escrita: 3 chamadas, sem sobra | verde |
| downloads em espaços diferentes não se misturam | verde |
| destino preexistente intacto e operação falha | **vermelho** — a 1ª chamada apagou o arquivo e a 2ª baixou por cima |
| arquivo alheio criado no instante da transferência | **vermelho** — apagado pelo SDK |
| limpeza que falha interrompe com as duas falhas | **vermelho** — repetia sobre estado desconhecido |

### O que mudou

- `ArquivoMinioClient.baixar`: cada chamada ao recurso toma posse do destino com
  `Files.createFile` (atômico, `O_EXCL`) e só então chama o SDK, com `CREATE_OR_REPLACE_EXISTING`
  e `LEAVE`. Falha da transferência descarta o parcial nesta classe, antes de chegar à
  repetição; falha no descarte vira `LimpezaDoParcialFalhouException` (causa = transferência,
  `falhaDaLimpeza()` e suprimida = limpeza). Colisão e limpeza que falhou não são repetidas.
- `comRepeticao` ganhou uma sobrecarga com filtro, usada só pelo download; o upload chama a forma
  de um argumento, igual às cópias de `videos` e `notificacao`, que não foram tocadas. A
  divergência está registrada no `AGENTS.md`, junto das outras de `comRepeticao`.
- Javadoc do cliente e do teste reescritos: o parágrafo desmentido saiu, e o do teste separa o
  que o SDK já fazia do que ficou vermelho até aqui.
- `ArquivoMinioAdapter`, `EspacoDeTrabalhoAdapter` e o use case: sem mudança. Interface,
  streaming em disco e tradução para `FalhaTransitoriaDeExtracaoException` preservados.

A falha na limpeza é provocada no teste trocando o parcial por um diretório não vazio no mesmo
caminho depois dos bytes em disco: `Files.delete` o recusa para qualquer usuário, `root` inclusive.

### Limites que ficam

- **A posse vale pelo caminho, não pelo arquivo.** Quem apagasse o parcial e criasse outro no
  mesmo caminho, dentro do diretório exclusivo da tentativa, teria o arquivo truncado ou apagado.
  Está escrito no javadoc de `baixar`; nenhum código além deste download escreve ali.
- Colisão numa **repetição** devolve só a `FileAlreadyExistsException`, sem a falha de
  transferência da chamada anterior. Não há cenário que a provoque de forma determinística sem
  costura nova.
- `IOException` do `createFile` que não seja colisão (por exemplo diretório já removido) ainda é
  repetida; não gasta chamada ao MinIO, só a espera.

### Validação

- `./mvnw test` da raiz, depois das correções da revisão: **462 testes**, 0 falhas, 0 erros
  (143 `videos`, 290 `extracao`, 29 `notificacao`). A primeira rodada reprovou o cenário
  concorrente por `ServiceConfigurationError` do Mutiny: o `supplyAsync` no common pool herdava o
  classloader de um `@QuarkusTest` anterior. O cenário passou a usar executor próprio.
- `CICLOS=45 SAIDA=/tmp/t102-travamento systemd-inhibit ... scripts/carga/travamento.sh`, com a
  stack recriada (`docker-compose.yml`, sem overlay, teto padrão de 45 s) e a imagem
  `ghcr.io/vandrep/fiapx-extracao:latest` reconstruída localmente (`sha256:497bb0ea…`, as duas
  réplicas conferidas): **45 ciclos sem travamento**, 1–2 s cada. A imagem é anterior às
  correções da revisão, que só trocaram a forma do filtro (sobrecarga e método nomeado) e javadoc.
- `scripts/smoke.sh` não rodou: não houve mudança de contrato, mensageria, Compose nem Dockerfile.
- `/code-review 0d46527` (padrões e spec): acatados o `AGENTS.md` desatualizado, o filtro nomeado,
  a sobrecarga que devolve o upload à forma das cópias, o motivo real do `LEAVE` no javadoc e o
  limite da posse por caminho. Os dois últimos itens de "Limites que ficam" são achados da revisão
  deixados de fora de propósito.
