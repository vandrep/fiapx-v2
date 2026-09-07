# A raiz de composição tem dois nomes de pacote depois do 068

- id: 081
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] Os três serviços nomeiam a raiz de composição do mesmo jeito, ou a diferença está escrita
- [ ] A regra `workersNaoDevemDeclararPacoteDeBordaHttp` segue verde nos três
- [ ] Nenhuma mudança de comportamento — o ticket é de layout
- [ ] `./mvnw test` verde a partir da raiz
