# Modelo de domínio e script de banco do serviço videos

- id: 009
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por: 007, 008

## Question

`videos` é o dono do estado. O enunciado exige entregar o "script de criação do banco de
dados" como documentação — então o esquema é entregável, não detalhe interno.

A decidir:

- Entidades e value objects do `core`: o que é **Vídeo**, o que é **Pacote**, e se a
  **Extração** é uma entidade persistida ou apenas a operação que o `extracao` executa.
- A máquina de estados como código: quem valida a transição (`RECEBIDO` → `PROCESSANDO` →
  `CONCLUIDO` | `FALHOU`) e o que acontece com uma transição inválida vinda de um evento
  fora de ordem.
- Esquema das tabelas, índices (a listagem filtra por dono e ordena por data), e o
  `init.sql` versionado — incluindo a criação dos databases por serviço.
- Migrations: Flyway ou `import.sql`/geração do Hibernate? O que é defensável na banca sem
  virar peso.
- Guarda de propriedade: onde exatamente mora a regra "só o dono vê o vídeo" — entidade,
  use case ou consulta do gateway. (Se ficar só na consulta, é fácil de furar depois.)
- Que gateways e presenters o `core` declara, seguindo o padrão do `AGENTS.md` do template.

**Restrição vinda do ticket 007**: `videos` precisa persistir o e-mail do dono (claim
`email` do token, capturado no upload) e o nome do arquivo original — sem os dois, o evento
`VideoFalhou` não pode ser montado e o `notificacao` fica sem o que mandar.

## Resolução

Uma tabela, sete use cases, e a máquina de estados repartida entre a entidade e o `WHERE`.
O script entregável é [`docker/postgres/init.sql`](../../../docker/postgres/init.sql).

### A Extração não é entidade do `videos`

Uma tabela só: `video`. O glossário diz que "uma Extração pode ser tentada mais de uma vez",
mas o `videos` **não vê tentativas** — ele vê no máximo três entregas do mesmo evento, sem
saber que são tentativas distintas, porque o `x-delivery-limit` é do broker e não trafega em
mensagem nenhuma. Persistir histórico exigiria inventar um dado que o contrato do ticket 007
não carrega. O resultado da Extração vira atributo do Vídeo. Coerente com o contrato HTTP,
onde o Pacote é sub-recurso justamente por não ter identidade própria.

### A máquina de estados mora em dois lugares, com papéis distintos

Esta é a decisão-mãe do ticket, e ela nasce de uma tensão real: o ADR 0001 e o
`mensagens.md` fixaram a guarda de unicidade como `UPDATE ... WHERE id = ? AND estado = ?`
— regra de negócio expressa como cláusula SQL —, enquanto o `AGENTS.md` do template manda
toda regra de negócio para o `core`.

Nenhum dos dois extremos serve. Só o `UPDATE` tira a regra do `core`, sem teste unitário e
sem o `ArchitectureConstraintsTest` protegendo coisa alguma. Só a entidade é
read-modify-write sem guarda: dois consumidores concorrentes do mesmo evento reentregue leem
`PROCESSANDO`, ambos concluem que a transição é legal, e ambos publicam `VideoFalhou` — o
e-mail duplicado que o ADR 0001 existe para impedir.

A divisão adotada:

| Pergunta | Quem responde |
|---|---|
| "esta transição é legal?" | `EstadoVideo`, no `core` — função pura, testável sem banco |
| "fui **eu** quem de fato mudou a linha?" | o `UPDATE` condicional, no adapter |

Não é regra duplicada: são perguntas diferentes. A entidade não consegue garantir
atomicidade; o banco não consegue explicar o domínio. O gateway devolve
`CompletableFuture<Boolean>` — `true` significa "a linha mudou" —, e o use case só publica
`VideoFalhou` quando vem `true`.

O grafo continua declarado **uma vez**: `EstadoVideo` sabe qual estado cada transição exige
como predecessor, e o use case passa esse predecessor ao gateway, que o usa no `WHERE`.

Registrado no [ADR 0002](../../adr/0002-maquina-de-estados-em-duas-camadas.md), porque a
tentação de "consertar" a duplicação aparente removendo uma das duas camadas é real.

### Transição ilegal é retorno, não exceção

`ExtracaoIniciada` chegando num Vídeo já `CONCLUIDO` devolve "não mudou nada" e o consumidor
dá **ack**. Sem exceção: o `mensagens.md` já classificou isso como comportamento correto, e
exceção no caminho esperado vira ruído de log e obriga todo consumidor a um `catch` só para
poder confirmar a mensagem. `IllegalStateException` fica reservada para transição que o grafo
não prevê de jeito nenhum (`FALHOU` → `CONCLUIDO`), que aí é bug e não reentrega.

### A guarda de propriedade é estrutural, não uma verificação

**Não existe `buscarPorId(UUID)` na interface do gateway.** Existe `buscarPorIdEDono(UUID,
Dono)` e `listarPorDono(...)`. A regra não é "verifique o dono", é "não há como pedir um
Vídeo sem dizer de quem" — impossível de furar por esquecimento numa sessão futura. O `404`
do Vídeo alheio cai naturalmente do `Optional` vazio, sem nenhum `if` decidindo entre `403` e
`404`.

O caminho de mensageria não abre exceção a isso: ele nunca lê Vídeo por id. Chama
`marcarConcluido(id, ...)` e recebe um boolean — a entidade não é devolvida.

### Sem Flyway: o script roda de verdade

`docker/postgres/init.sql` montado em `docker-entrypoint-initdb.d`, com
`quarkus.hibernate-orm.schema-management.strategy=validate` em `%prod` (e `drop-and-create`
em `%dev` e `%test`, como já está). O `validate` é o que impede o script de virar
documentação apodrecida: divergir das entidades derruba o serviço no boot.

Flyway foi rejeitado por custo desproporcional: ele é JDBC, então exigiria acrescentar
`quarkus-jdbc-postgresql` e um datasource Agroal **só para migrar**, ao lado do
`reactive-pg-client` que a aplicação de fato usa — duas conexões e duas configurações para
uma migração só. Em 5,5 semanas com uma entrega, não existe V2.

O database é criado pelo `POSTGRES_DB=fiapx_videos` do Compose, não por `CREATE DATABASE` no
script. Um comentário no topo do arquivo registra que existe **um** database porque só o
`videos` tem estado — a decisão fica visível para quem lê o entregável. Sem role separada: o
usuário do `POSTGRES_USER` é o dono.

### O que a linha guarda

Além do óbvio, três resoluções que não eram óbvias:

- **`chave_pacote`** — obrigatória: é a origem do stream de download.
- **`quantidade_frames`** — guardada mesmo **não aparecendo na API**. Chega de graça no
  `ExtracaoConcluida` e é a única prova numérica de que a Extração funcionou.
- **Duas colunas de tamanho** — `tamanho_bytes` (do Vídeo, é o que a API expõe) e
  `tamanho_pacote_bytes` (do ZIP). O contrato HTTP e o `ExtracaoConcluida` usam o mesmo nome
  para coisas diferentes; unificar seria a economia que vira bug de demo.

**`finalizado_em`, uma coluna só**, para `CONCLUIDO` e `FALHOU` — são terminais e mutuamente
exclusivos. No banco o nome é honesto; na saída, o presenter o entrega como `concluidoEm`,
que é o nome que o contrato publicado usa. Traduzir domínio→JSON já é o trabalho do
presenter; renomear coluna depois é barato, mudar contrato publicado não é.

### Tipos: `varchar` + `CHECK`, não enum nativo

`estado VARCHAR(20)` e `motivo VARCHAR(30)`, ambos com `CHECK`, mapeados com
`@Enumerated(EnumType.STRING)`. Enum nativo do Postgres é penoso de alterar e acrescenta
atrito no mapeamento pelo `reactive-pg-client`; o `CHECK` dá a mesma proteção e, num script
que a banca vai ler, é autoexplicativo.

### Um índice, e o que **não** foi indexado

`(dono_sub, recebido_em DESC)`, além da PK. **Sem índice em `estado`**: quatro valores
possíveis, baixa seletividade, e pôr a coluna no índice composto ajudaria só o caso filtrado
enquanto atrapalha o não-filtrado, que é o default da API. Índice que o planner ignora é
enfeite que a banca pergunta e ninguém sabe defender.

### O `core`: entidades, VOs e as duas factories

- **`Video`** — entidade. Duas factories estáticas, porque o Vídeo tem duas origens:
  `Video.novo(nome, tamanhoBytes, dono, chaveVideo)` (gera `UUID.randomUUID()` e
  `Instant.now()`, estado `RECEBIDO`) e `Video.reconstituir(...)`, que aceita qualquer estado
  válido e não roda invariantes de criação. `reconstituir` é pública porque o adapter está em
  outro pacote — vazamento assumido, é o preço de Clean Architecture em Java sem JPMS.
- **A impureza fica contida na criação.** As transições **recebem o instante de fora**
  (`marcaComoConcluida(concluidaEm)` usa o instante do evento, não o relógio), então só
  `Video.novo` é não-determinístico e os testes das transições são determinísticos. Injetar
  um `Clock` foi rejeitado como cerimônia.
- **`Dono`** — `record Dono(String sub, String email)`, validando apenas **não-branco**.
  Viajam sempre juntos (token → upload → linha → `VideoFalhou`), e a assinatura
  `buscarPorIdEDono(UUID, Dono)` fica à prova do erro clássico de trocar dois `String` de
  posição. A consulta usa só `dono.sub()` no predicado, documentado no gateway.
  **Sem validação de formato UUID no `sub`**: amarraria o domínio ao formato de id do
  Keycloak. *(Resolve por antecipação metade da pergunta que a névoa do realm carrega.)*
- **`EstadoVideo`** — enum, dono do grafo de transições.
- **`MotivoFalha`** — enum, com um valor a mais que o contrato de mensagens: **`DESCONHECIDO`**.
  A conversão no consumidor é tolerante — um código publicado por um `extracao` mais recente
  cai em `DESCONHECIDO` em vez de derrubar a mensagem. É o que torna o enum compatível com a
  estratégia "só aditivo + tolerant reader" do ticket 007, sem abrir mão do `CHECK` no banco
  nem do `@Schema` de enum no OpenAPI. Sem o enum, `motivo` vira string livre que ninguém
  valida.
- **`Pagina<T>(conteudo, pagina, tamanho, total)`** — DTO do `core` para a listagem.

### Sete use cases, em `core.usecases.video`

| Use case | Origem |
|---|---|
| `EnviarVideoUseCase` | `POST /videos` |
| `ListarVideosDoDonoUseCase` | `GET /videos` |
| `ConsultarVideoUseCase` | `GET /videos/{id}` |
| `BaixarPacoteUseCase` | `GET /videos/{id}/pacote` |
| `RegistrarExtracaoIniciadaUseCase` | evento `ExtracaoIniciada` |
| `RegistrarExtracaoConcluidaUseCase` | evento `ExtracaoConcluida` |
| `RegistrarExtracaoFalhouUseCase` | evento `ExtracaoFalhou` — **o único que publica** |

O prefixo `Registrar` nos três de mensageria é deliberado: eles não *fazem* a Extração,
registram que ela aconteceu.

### Gateways, sender e presenters

| Contrato do `core` | Adapter | Camada |
|---|---|---|
| `VideoGateway` | `VideoDataSourceAdapter` | `framework.db` |
| `ArquivoGateway` | `ArquivoMinioAdapter` | `framework.service` |
| `EventoSender` (`core.interfaces.sender`) | `EventoRabbitDispatcher` | `framework.dispatcher` |

**Um sender, não dois.** `ExtracaoSender`/`NotificacaoSender` nomearia a interface pelo
*consumidor*, e o `videos` publica em exchange, não em serviço — ele não deveria saber quem
escuta.

Presenters: `VideoPresenter` (representação única) e `VideosPaginadosPresenter`. O adapter
resolve o `total` com `page()` + `count()` do Panache.

**O download não tem presenter** — bufferizar bytes num campo privado contraria o "nunca
`toBytes`" do ticket 005. `BaixarPacoteUseCase` faz as duas guardas (é meu? está
`CONCLUIDO`?) e devolve um DTO com nome sugerido, tamanho e o fluxo tipado como
**`java.util.concurrent.Flow.Publisher<ByteBuffer>`** — JDK puro, então o `core` declara
streaming sem importar Mutiny nem Vert.x, e o adapter faz a ponte para `RestMulti` na borda.

### Quem monta a chave de objeto

O formato é decisão do ticket 011, mas *onde o código a constrói* é deste: **o `Video` carrega
as duas chaves como `String` opaca, e quem as constrói é o `ArquivoGateway`, não o `core`**.
`ArquivoGateway.gravarVideo(idVideo, nome, fluxo)` **devolve** a chave que gravou, e
`Video.novo(...)` a recebe pronta. A entidade sabe *onde este Vídeo está* sem conhecer a
convenção — é a mesma promessa que o ticket 007 fez ao `extracao`, aplicada uma camada acima.
Consequência: a decisão do 011 pousa no adapter, sem tocar em entidade, esquema nem contrato.

### O Vídeo órfão em `RECEBIDO`

`EnviarVideoUseCase` toca três sistemas sem transação comum. Ordem fixada: **objeto no MinIO
→ linha no banco → publicação de `ExtrairVideo`**, para que nenhum passo referencie algo que
ainda não existe. Se a publicação falhar de vez, sobra uma linha `RECEBIDO` que nunca vira
nada — sem comando não há extração, sem extração não há evento, e o `x-delivery-limit` não
protege mensagem que nunca entrou na fila.

O `@Retry` do ADR 0001 cobre a falha transitória do publish; o que sobra é o `videos` morrer
entre o `INSERT` e o `publish`. A mitigação real é transactional outbox, e ela virou o
**ticket 018**, em vez de ser decidida de afogadilho aqui.

**Resolvido desde então**: o ticket 018 recusou o outbox canônico e fez da própria tabela
`video` o outbox — as colunas `comando_publicado_em` e `falha_publicada_em`, mais uma
varredura de reconciliação ([ADR 0003](../../adr/0003-reconciliacao-por-varredura.md)). A
ordem fixada acima continua valendo; o que mudou é que agora existe quem repare quando ela
for interrompida.

### Fora deste ticket

O `%prod.quarkus.hibernate-orm.schema-management.strategy=validate` e as entidades Panache
não foram escritos aqui: são o ticket 016, que é test-first. Note que `PanacheEntity` **não
serve** — ele fixa `id` como `Long` sequencial, e o contrato exige UUID gerado no upload.
`VideoEntity` estende `PanacheEntityBase` com `@Id` explícito, divergindo do exemplo do
template numa dimensão que o `ArchitectureConstraintsTest` não cobre.
