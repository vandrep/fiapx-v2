# Verificar o estado após expiração pela borda HTTP

- id: 046
- label: wayfinder:bug
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Origem

Achado do eixo Standards da revisão de `3a3ec95...270b891`, convertido em ticket com
aprovação do usuário. O passo BDD que verifica que o Vídeo continua em determinado estado
consulta a entidade persistida diretamente. A regra de BDD do repositório exige observar
o comportamento do serviço `videos` por status, corpo e cabeçalhos HTTP. A consulta direta
não detectaria uma representação pública incorreta após a descoberta da expiração.

## O que entregar

O cenário de aceite comprova, pela experiência do usuário autenticado, que o download
de um Pacote expirado responde `410` e que uma consulta posterior mantém o Vídeo em
`CONCLUIDO`, conforme o contrato HTTP. As asserções de comportamento do cenário passam
pela borda pública.

## Condições de aceite

- [x] Manter o cenário Gherkin em português e a entrada pela borda HTTP autenticada.
- [x] Verificar que o download de um Pacote expirado responde `410` com o erro contratado.
- [x] Consultar em seguida o mesmo Vídeo pela API e verificar resposta `200` com estado
  `CONCLUIDO`.
- [x] Remover a leitura direta da entidade persistida das asserções desse passo BDD.
- [x] Preservar a verificação da chave interna, se necessária, em teste de persistência
  apropriado, sem expor essa chave na API.
- [x] Executar o cenário com a infraestrutura real e a suíte a partir da raiz.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

O cenário `Pacote expirado no armazenamento é 410, não mais` passou a fazer a segunda
verificação pela mesma borda que a primeira: depois do `410` com o `problem+json` de
"Pacote expirado", ele emite `Quando eu consulto o Vídeo enviado` e confere `200` com
`estado` igual a `CONCLUIDO`. Os três passos novos já existiam — são os mesmos do cenário
de consulta e do de envio —, então a mudança no `videos.feature` não trouxe step novo.

O passo `o Vídeo continua em {string}` saiu do `VideosSteps`. Ele era a única asserção do
arquivo que lia a entidade persistida, e por isso a única que não teria acusado uma
representação pública errada: o `410` podia vir acompanhado de um `GET` devolvendo estado
divergente, ou de um vazamento de `chavePacote` na resposta, sem nada ficar vermelho. As
duas leituras diretas que restam no arquivo são **montagem** de cenário — pôr o Vídeo em
CONCLUIDO e apagar o objeto do bucket —, que é o que o javadoc da classe já declarava.

A chave interna não voltou como asserção direta, e a revisão mostrou que ela não estava
coberta onde eu tinha escrito que estava. Os dois testes que eu citei cobrem metades
diferentes: `VideoDataSourceAdapterTest.concluidaPersisteOsMesmosCamposQueAEntidade` confere
o `chavePacote` na ida e volta contra o Postgres de verdade, mas fora do fluxo do `410`; e
`BaixarPacoteUseCaseTest.oEstadoDoVideoNaoMudaAoDescobrirQueOPacoteSumiu` confere que
descobrir a ausência não mexe no estado nem apaga a chave, mas contra gateway em memória.
Nenhum dos dois é o que a asserção removida fazia — a chave no Postgres **depois** do `410`.

O que fechou essa lacuna foi um quarto passo, ainda pela borda: o cenário baixa o Pacote de
novo no fim e exige `410` outra vez. Como `Video.temPacote()` olha só o estado, um segundo
`410` só sai se o registro não foi reescrito — estado alterado daria `409`, e `chavePacote`
apagada quebraria na abertura do objeto em vez de virar Pacote expirado. Não é a mesma
asserção, é a consequência dela observável de fora, que é o que o ticket pede.

A chave em si segue fora da API: o `VideoViewModel` não tem esse campo, então o BDD não
teria como lê-la mesmo que quisesse — e é justamente por isso que a prova precisou ser
indireta.

**Suíte verde a partir da raiz**, com infraestrutura real. Os 14 cenários do `videos`
passam, e o `./mvnw test` do agregador fecha em 120 testes no `videos`, 268 no `extracao` e
24 no `notificacao`. A stack do Compose estava
de pé nesta máquina e o Keycloak dela ocupa a 8081, a porta de teste do Quarkus; os testes
rodaram com `-Dquarkus.http.test-port` apontando para uma porta livre em vez de derrubar a
stack, como no ticket 045. Circunstância da máquina, não do projeto.
