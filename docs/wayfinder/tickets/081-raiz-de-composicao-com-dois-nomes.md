# A raiz de composição tem dois nomes de pacote depois do 068

- id: 081
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `08d76ed...c592711`, convertido em ticket com aprovação
do usuário.

## O problema

O [ticket 068](068-framework-web-em-worker-sem-borda-http.md) mudou `ExtracaoConfiguration` e
`NotificacaoConfiguration` de `framework.web` para `framework.configuration`, porque worker sem
borda HTTP não tem o que declarar num pacote chamado `web`. O argumento vale, e a regra
`workersNaoDevemDeclararPacoteDeBordaHttp` passou a cobrá-lo.

`VideosConfiguration` continua em `framework.web`. As três classes cumprem o mesmo papel — raiz
de composição, produtoras de configuração — e agora moram em dois pacotes de nomes diferentes.
A divergência é nova: antes do 068 as três estavam no mesmo lugar.

Isso não é violação de regra nenhuma. É custo de navegação: quem procura a configuração do
`videos` no lugar onde ela está nos outros dois não a acha, e o `AGENTS.md` não diz por que ela
está em outro lugar.

## O que entregar

Uma das duas, decidida e escrita:

- `VideosConfiguration` acompanha as outras duas para `framework.configuration`, e os três
  serviços passam a nomear a raiz de composição igual. `framework.web` no `videos` fica para o
  que é de fato borda HTTP.
- Ou o `videos` fica onde está, e o `AGENTS.md` registra por que a borda pública mantém a
  configuração junto da web enquanto os workers a separam.

## Critérios de aceite

- [x] Os três serviços nomeiam a raiz de composição do mesmo jeito, ou a diferença está escrita
- [x] A regra `workersNaoDevemDeclararPacoteDeBordaHttp` segue verde nos três
- [x] Nenhuma mudança de comportamento — o ticket é de layout
- [x] `./mvnw test` verde a partir da raiz

## Resolução

**Escolhida a primeira das duas opções: `VideosConfiguration` acompanhou as outras duas.** Ela
saiu de `br.com.fiapx.videos.framework.web` para `br.com.fiapx.videos.framework.configuration`, e
os três serviços voltaram a nomear a raiz de composição do mesmo jeito.

**Por que unificar em vez de escrever a diferença.** A alternativa exigia justificar no
`AGENTS.md` por que a borda pública mantém a configuração junto da web, e não há justificativa
para dar: `VideosConfiguration` não é borda HTTP em nenhum sentido — não tem `@Path`, não tem
`Resource`, não conhece requisição. É a mesma classe de coisa que `ExtracaoConfiguration` e
`NotificacaoConfiguration`, e o [068](068-framework-web-em-worker-sem-borda-http.md) já tinha
decidido, para essas duas, que raiz de composição não mora em pacote de borda. O argumento dele
não dependia de o serviço ser worker; dependia de a classe não ser web. Aplicá-lo só a dois dos
três era o acidente, e o 068 não o notou porque a regra que ele escreveu isenta o `videos` por
construção.

**A mudança é de layout e só.** `VideosConfiguration` é produtora CDI descoberta por scan e
**não é importada por nenhuma classe do repositório** — a movimentação não tocou um único
`import`. As duas únicas menções ao nome estão em `ExtracaoConfiguration` (javadoc, "mesmo papel
do `VideosConfiguration`") e em `docs/specs/publicar-e-marcar-um-caminho.md`, ambas pelo nome
simples, ambas ainda verdadeiras. Nenhum `.properties`, `Dockerfile` ou script referencia o
pacote.

`framework.web` no `videos` ficou com o que é de fato borda HTTP: `VideosResource`,
`ProblemDetail`, `ProblemDetailMappers`, `SegurancaOpenApiFilter`.

**Nenhuma regra nova.** `workersNaoDevemDeclararPacoteDeBordaHttp` segue verde nos três — ela
proíbe `framework.web` fora do `videos`, e no `videos` continua permitido, que é onde a borda
mora. Uma regra que exigisse a configuração em `framework.configuration` cobraria layout com um
exemplo por serviço, e o `AGENTS.md` passou a carregar a convenção em prosa, ao lado da oitava
regra que a originou. As três cópias do teste arquitetural não mudaram, e a guarda de identidade
byte a byte passa.

**Validações**: `./mvnw -pl videos compile` limpo; **`./mvnw test` a partir da raiz verde, 450
testes**; `scripts/verifica-testes-arquiteturais.sh` e `scripts/verifica-ackmanual.sh` passam.
Nenhuma mudança de comportamento — nenhum arquivo fora do `.java` movido e do `AGENTS.md` foi
tocado por este ticket.

**Build incremental verificado, porque renome de pacote costuma deixar `.class` órfão.** A
revisão levantou um `ClassNotFoundException` de `SegurancaOpenApiFilter` sem `clean`; **não
reproduz** nesta árvore. O `videos/target/classes/.../framework/web/` não tem
`VideosConfiguration.class` remanescente, e `./mvnw -pl videos test` sem `clean` sobe e passa,
inclusive o `ErroInternoNoOpenApiTest`, que exercita justamente aquele filtro. O sintoma da
revisão veio de um build concorrente durante o *stash* desta sessão, quando a árvore estava
momentaneamente no layout antigo — não é propriedade do que foi entregue.

A suíte só fechou verde depois de instalar o `ffmpeg` no host, que faltava e derrubava 5
cenários do `extracao` — alheio a este ticket, e detalhado na `## Resolução` do
[080](080-custo-de-teste-do-blip-sem-substituto.md), que correu na mesma sessão.
