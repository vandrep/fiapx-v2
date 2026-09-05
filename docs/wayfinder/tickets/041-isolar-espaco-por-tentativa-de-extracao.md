# Isolar o espaço temporário de cada tentativa de Extração

- id: 041
- label: wayfinder:bug
- status: resolvido
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem e evidência

Achado do eixo Spec da revisão de `3a3ec95...f6baade`, convertido em ticket com aprovação
do usuário. Duas instâncias do adapter real, usando uma raiz temporária compartilhada e o
mesmo identificador de Vídeo, reproduziram a colisão: preparar a segunda execução removeu
o frame da primeira; limpar a primeira removeu o frame da segunda. O Compose compartilha
o volume entre réplicas. A reprodução confirmou a remoção dos arquivos, sem executar o
fluxo completo de Extração concorrente.

## O que entregar

Duas réplicas devem poder receber comandos duplicados para o mesmo Vídeo sem destruir o
trabalho uma da outra. Cada tentativa deve possuir seu próprio espaço temporário e limpar
apenas os arquivos que lhe pertencem. Preservar a tolerância a duplicatas do ADR 0003, as
guardas de transição do ADR 0002 e a recuperação do espaço abandonado após crash.

## Condições de aceite

- [x] Reproduzir a colisão em teste de regressão com duas execuções sobre a mesma raiz.
- [x] Preparar e limpar uma tentativa não remove downloads, frames ou Pacote temporário
  de outra tentativa do mesmo Vídeo.
- [x] Processar simultaneamente o mesmo Vídeo válido em duas réplicas e verificar o
  desfecho `CONCLUIDO` e a integridade do Pacote disponível pela borda pública.
- [x] A duplicata não produz falha definitiva por colisão de arquivos; os eventos
  duplicados continuam respeitando a guarda de transição.
- [x] A limpeza após sucesso ou falha e a recuperação de órfãos após crash continuam
  funcionando, preservando trabalho ativo de outras réplicas.
- [x] Executar a suíte a partir da raiz, o smoke e o ensaio de conservação aplicável,
  registrando resultados e eventuais falhas preexistentes separadamente.

## Dependências

Nenhuma. Pode começar imediatamente; não depende da migração geral dos cenários BDD.

## Resolução

O scratch deixou de ser "um diretório por Vídeo" e passou a ser **um diretório por
tentativa**. `EspacoDeTrabalhoGateway.prepararNovo(idVideo)` continua recebendo o id, mas
agora devolve um espaço exclusivo daquela execução, e `limpar` deixou de receber o
`UUID` para receber o `Path` que `prepararNovo` devolveu — a assinatura é a mudança de
verdade: com o id, "limpar a minha tentativa" e "limpar a tentativa da outra réplica" eram o
mesmo comando, e nenhuma quantidade de cuidado no chamador consertaria isso.

- **`EspacoDeTrabalhoAdapter`**: `Files.createTempDirectory(raiz, idVideo + "-")` no lugar do
  apaga-e-recria de `{raiz}/{idVideo}`. A criação é atômica, então duas réplicas que preparem
  no mesmo instante não têm como receber o mesmo caminho; o id fica no prefixo porque quem
  abre o volume à mão depois de um crash precisa saber de qual Vídeo é o scratch.
- **`limpar(Path)` recusa o que não nasceu dali**: só apaga filho direto da raiz. Sem essa
  guarda, um caminho errado levaria o `Files.walk` recursivo a apagar arquivos de outro dono —
  o preço de trocar um id por um caminho.
- **Varredura periódica nova** (`quarkus-scheduler` no `extracao`, `@Scheduled` em
  `framework`), com o mesmo corpo e o mesmo gate por idade da varredura de boot — ver o item
  seguinte para o porquê.
- **`ProcessarExtracaoUseCase`** guarda o diretório da tentativa e o passa à limpeza do
  `finally` lógico. A tolerância a duplicatas do ADR 0003 e as guardas do ADR 0002 não foram
  tocadas: o que muda é o disco, não a máquina de estados.
- **A varredura de órfãos ficou mais precisa e ganhou um segundo gatilho**: cada filho da raiz
  agora é uma tentativa, não um Vídeo, então o gate por idade do ticket 027 julga cada
  tentativa pela sua própria idade — uma tentativa abandonada por crash some sem levar junto a
  tentativa viva do mesmo Vídeo na réplica vizinha. Além do boot, ela passou a rodar a cada 15
  min (`@Scheduled`, `quarkus-scheduler` novo no `extracao`, mesmo padrão da varredura do ADR
  0003 no `videos`). O periódico não é zelo: quem recuperava o espaço da tentativa morta era o
  apaga-e-recria do `prepararNovo`, e ele foi embora **porque era o defeito** — "tentativa
  anterior" e "tentativa viva na outra réplica" eram indistinguíveis. Sem ele, sobrava só o
  boot, e o boot não alcança o órfão da própria réplica: quem morre e volta acorda com o
  scratch abandonado ainda recente, e o gate por idade — corretamente — o preserva. Isso foi
  **medido**, não deduzido: o scratch da réplica morta no ensaio de conservação sobreviveu ao
  restart dela e ainda estava no volume minutos depois.
- **Documentação corrigida onde ela passou a mentir**: o comentário do
  `application.properties`, o do `extracao/Dockerfile` e o item de limites conhecidos em
  `docs/arquitetura.md`, que atribuía ao ticket 027 a correção inteira do volume compartilhado
  — o 027 fechou a metade da varredura de boot; esta é a outra metade.

### Validação

- **Regressão, medida nos dois sentidos** (`EspacoDeTrabalhoAdapterTest`): duas instâncias do
  adapter sobre a mesma raiz, mesmo id de Vídeo. Com o código novo, cada uma recebe seu
  diretório, o frame de uma sobrevive ao preparo da outra e a limpeza de uma não alcança a
  outra. Revertido só o nome do diretório para o formato antigo, o teste reprova em
  `cada tentativa precisa do seu proprio espaco ==> expected: not equal but was:
  </tmp/fiapx-extracao-test/9462e0f2-...>`; código restaurado em seguida.
- **BDD, pela borda de mensageria**: cenário novo em `extracao.feature` publica o comando
  **duplicado** na routing key real e cobra dois `extracao.concluida` (um por comando), o
  Pacote no bucket, o zip abrindo com entradas não vazias — CRC conferido pelo `ZipFile`, que
  `headObject` não enxerga — e nenhum `extracao.falhou`. Numa réplica só o `prefetch=1`
  serializa a duplicata; o que este cenário trava é o desfecho, e a concorrência de verdade foi
  medida no Compose, abaixo.
- **Suíte na raiz**: `./mvnw test -Dquarkus.http.test-port=0`, BUILD SUCCESS, 406 testes
  (114 videos, 268 extracao, 24 notificacao), zero falhas, erros ou skips. A porta dinâmica é
  ambiental, o mesmo motivo dos tickets 039, 040 e 042: o Keycloak do Compose de pé ocupa a
  8081 do `@QuarkusTest`.
- **`scripts/smoke.sh`**: uma execução limpa, os nove passos verdes, com a imagem local do
  `extracao` reconstruída a partir desta mudança.
- **Ensaio de duas réplicas, o antes e o depois**, agora versionado como
  `scripts/carga/duplicata-em-replicas.sh` — a condição de aceite pede concorrência de verdade,
  que nem o `smoke.sh` (um Vídeo de cada vez) nem a suíte (um consumidor só, com
  `max-outstanding-messages=1` serializando a duplicata) alcançam. Ele sobe duas réplicas,
  envia o Vídeo pela borda pública e publica os comandos duplicados na
  `fiapx.comandos`/`extracao.extrair` pela API de management, com os quatro critérios impressos
  antes de rodar:
  - *Antes* (imagem construída de `5411fb6`): o Vídeo válido terminou em **`FALHOU` com
    `ARQUIVO_INVALIDO`**, `GET /videos/{id}/pacote` respondeu 409 e o log mostra a réplica 1
    publicando `extracao.falhou` às 20:47:23,523 e `extracao.concluida` 238 ms depois — o
    mesmo Vídeo produzindo os dois desfechos, com a guarda de transição do `videos` fixando o
    primeiro. É o defeito do ticket 025 de volta por outra porta: um h264 válido entregue ao
    usuário como arquivo inválido.
  - *Depois*: duas tentativas do mesmo Vídeo coexistem no volume compartilhado, cada uma no
    seu diretório (`{id}-166324344662191091` e `{id}-5089711205402232351`), o Vídeo termina em
    **`CONCLUIDO`**, o Pacote baixa pela borda pública com 200, `unzip -t` passa e traz os três
    frames esperados, e nenhum diretório daquele Vídeo sobra no volume. Quatro execuções ao
    todo, o mesmo resultado.
  - A guarda de transição do ADR 0002 aparece nas duas pontas do ensaio, e é do `videos`: no
    *antes*, ela fixou o primeiro terminal e o `CONCLUIDO` que chegou 238 ms depois foi
    ignorado; no *depois*, os três comandos duplicados produziram um Vídeo em `CONCLUIDO`, não
    três desfechos. A prova unitária dela continua na suíte do `videos` (ticket 039); nada
    neste diff a tocou.
  - **Varredura periódica, medida no container**: worker subido com
    `FIAPX_EXTRACAO_INTERVALO_DA_VARREDURA=10s` e `FIAPX_EXTRACAO_IDADE_MINIMA_DO_ORFAO_MINUTOS=1`
    sobre o volume real; um diretório plantado depois do boot com mtime de quatro horas atrás
    desapareceu na varredura seguinte, sem que nada mais fosse tocado.
- **`scripts/carga/conservacao.sh mata-extracao`** (400 envios, 4 réplicas, uma morta no meio
  da drenagem): os cinco critérios aprovados — 0 recusas, 400/400 terminais em 31s (limite
  300s), 0 presos, amostra de 10 conferida pela API como dono, e **0 Vídeos válidos declarados
  `FALHOU`**. O `AGENTS.md` avisa que este ensaio reprovava de propósito; os defeitos que o
  faziam reprovar são os do ticket 027, já fechados, e esta rodada não encontrou nenhum novo.

### Achados da revisão de dois eixos, acolhidos

A revisão rodou sobre a árvore antes do commit. **Standards** não achou violação dura de
padrão documentado — camadas, cópias do teste arquitetural, BDD pela borda e contrato de
mensagens intactos — e apontou smells no harness de teste: o laço de espera duplicado na
`BordaDeMensageria`, o par de mapas que nascia e morria junto, um `quantidadeDe` público sem
chamador de fora, o javadoc da classe de teste que ainda anunciava só a varredura, e uma
limpeza recursiva reimplementada dentro de um teste. Todos acolhidos: os dois mapas viraram
um `Map<String, List<JsonObject>>`, as duas esperas viraram um `sondar` que **drena antes de
decidir** — o retorno final do `procurarEvento` era inalcançável na prática —, e o resto foi
corrigido no lugar.

**Spec** achou três coisas que mudaram o resultado, e foram as que mais renderam:

- A condição de aceite das duas réplicas só existia como evidência manual, não reexecutável.
  Virou `scripts/carga/duplicata-em-replicas.sh`, com critério fixado antes de rodar.
- `assertNotEquals` no dublê do use case provava que o **dublê** gera caminhos distintos, não
  o código de produção. Saiu; o que ficou é a asserção que cobra o use case (cada execução
  limpa o espaço que ela mesma preparou), e a prova do isolamento mora no teste do adapter.
- O acúmulo de órfãos: sem o apaga-e-recria, nada reclamava o espaço da tentativa morta antes
  do próximo boot. É o achado que trouxe a varredura periódica acima — e a conferência do
  volume depois do ensaio de conservação mostrou que não era hipótese.

Dois pontos menores do mesmo eixo também entraram: a guarda de `limpar` saiu de dentro do
`executarBloqueante` (lá dentro, erro de programação virava `FalhaTransitoria`, e falha
transitória volta para a fila como mais uma duplicata) e a comparação com a raiz foi invertida
para não levantar `NullPointerException` em caminho sem pai. O teste correspondente deixou de
aceitar `Exception.class` e passou a cobrar `IllegalArgumentException` na causa.

### Falhas ambientais, separadas do resultado

- A porta 8081 do `@QuarkusTest` colide com o Keycloak do Compose de pé — daí
  `-Dquarkus.http.test-port=0` em toda execução de suíte. Não é do código.
- A primeira tentativa do ensaio de conservação morreu em `failed to set up container
  networking: network ... not found`, a mesma rede corrompida de Compose que o ticket 042
  registrou. `docker compose down` seguido de `docker network prune` e nova execução
  resolveu, sem nenhuma mudança de código no meio.
