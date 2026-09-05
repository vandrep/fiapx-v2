# Serializar as consultas da listagem

- id: 045
- label: wayfinder:bug
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Spec da revisão de `3a3ec95...270b891`, convertido em ticket com
aprovação do usuário. A listagem dispara a busca da página e a contagem simultaneamente
na mesma sessão reativa. O ticket 017 já documenta corrupção da sessão por consultas
concorrentes em outro fluxo. A fonte local do Panache confirma que a contagem executa
consulta assíncrona nessa instância; a falha específica da listagem não foi reproduzida.

## O que entregar

O usuário recebe a página dos próprios Vídeos com conteúdo e total corretos, sem
operações concorrentes na mesma sessão de persistência. A consulta da página e a
contagem devem ser encadeadas, preservando o contrato HTTP de paginação, filtro por
estado e ordenação por recebimento decrescente.

## Condições de aceite

- [x] Executar a busca da página e a contagem sequencialmente na sessão usada pela listagem.
- [x] Verificar pela API uma listagem com mais de uma página: conteúdo, total, número da
  página, tamanho e ordenação correspondem aos dados preparados.
- [x] Verificar que o filtro por estado afeta tanto o conteúdo quanto o total.
- [x] Verificar com dois donos que conteúdo e total não incluem Vídeos do outro usuário.
- [x] Exercitar requisições concorrentes de listagem contra a persistência real e registrar
  o resultado, distinguindo eventual reprodução do defeito anterior da validação da correção.
- [x] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

`VideoDataSourceAdapter.listarPorDono` deixou de combinar página e contagem num
`Uni.combine().all()` e passou a **encadeá-las**: `page(...).list()` e, no `flatMap`, o
`count()`. As duas continuam na mesma sessão de `Panache.withSession`, agora uma de cada
vez. O contrato HTTP não mudou — paginação, filtro por estado e ordenação por recebimento
decrescente saem iguais.

**A reprodução do defeito falhou, e isso está registrado no teste.** O
`ListagemConcorrenteTest` sobe a borda de verdade contra o Postgres dos Dev Services, envia
Vídeos pela API e dispara uma rajada de listagens simultâneas conferindo conteúdo, total,
página, tamanho e ordem em cada resposta. Com o `Uni.combine()` original ele passou — 40
requisições simultâneas, e também numa sonda mais dura e temporária, não versionada, de 150
requisições sobre 40 Vídeos com página de 20. E a rajada tem um limite conhecido: a corrida é
*dentro* de uma requisição, e cada requisição tem sessão própria, então a simultaneidade
entre elas não abre a janela — ela só põe pool e event loop sob disputa. Ou o Hibernate
Reactive serializa as duas consultas por baixo nesta versão, ou a janela não se abre por
carga. O teste vale como **validação da correção**, não como reprodução.

Como nenhuma asserção de resultado distingue a versão certa da errada, a cerca é o
`ListagemSerializadaTest`, que lê o fonte do adapter, ignora comentários e reprova
`Uni.combine` dentro de `listarPorDono`, exigindo o `flatMap` no lugar. Mesmo motivo do
`BordaDoEnvioSemTransacaoTest` do ticket 040 — sem ela o defeito volta sem nada ficar
vermelho —, mas não a mesma técnica: aquele usa reflexão sobre anotações, este lê o `.java`
por caminho relativo, como o `ArchitectureConstraintsTest`, e portanto depende do CWD ser o
módulo. **Ele guarda a forma, não a semântica**: `Uni.join`, um `combine` guardado em
variável ou duas subscrições manuais passariam. É a cerca barata contra a reintrodução
literal, não uma prova de serialização.

Pelo lado da API, os cenários de listagem do `videos.feature` cresceram: a paginação agora
percorre as duas páginas e confere `pagina`, `tamanho`, total e a ordem dos nomes; o cenário
de dois donos ganhou a conferência da ordem; e o filtro por estado passou a ter Vídeos em
dois estados e é conferido em três sentidos — o estado que casa com um só Vídeo, o estado
que casa com dois **com página de tamanho 1** (é aí que `total` deixa de poder ser
`conteudo.size()`) e o estado que não casa com nenhum, que era a cobertura do cenário
anterior.

**Suíte verde a partir da raiz**: 120 testes no `videos`, 268 no `extracao`, 24 no
`notificacao`. A stack do Compose estava de pé nesta máquina e o Keycloak dela ocupa a 8081,
a porta de teste do Quarkus; os testes rodaram com `-Dquarkus.http.test-port=0` em vez de
derrubar a stack. Isso é circunstância da máquina, não do projeto.
