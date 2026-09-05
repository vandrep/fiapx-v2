# Dublês de transição não guardam nada, e um javadoc descreve API que não existe mais

- id: 039
- label: wayfinder:bug
- status: resolvido
- assignee:
- bloqueado-por:

Achados pelo `/code-review` de `9481324^..HEAD` sobre o serviço `videos`, no eixo Standards.
Nenhum dos dois nasceu da extração dos publicadores (spec
[`publicar-e-marcar-um-caminho`](../../specs/publicar-e-marcar-um-caminho.md), que passou
limpa nos dois eixos): ambos entraram em `b1f9cd1` e `183539b`, quando a decisão de transição
mudou de `Optional<Video>` para `Boolean` e passou a ser executada no domínio.

## 1. O dublê de `VideoGateway` nunca reprova uma transição

`GatewaysEmMemoria.Videos` implementa as três guardas assim:

```java
public CompletableFuture<Boolean> marcarFalha(UUID id, Instant falhouEm, MotivoFalha motivo) {
    var video = armazenados.get(id);
    return CompletableFuture.completedFuture(proximaTransicaoMudaLinha
            && video != null && video.estado() == EstadoVideo.FALHOU);
}
```

`marcarIniciada` e `marcarConcluida` seguem o mesmo molde, cada uma comparando com o estado
**posterior** à transição (`PROCESSANDO`, `CONCLUIDO`, `FALHOU`). Só que o use case já executou
a transição no domínio antes de chamar o gateway — o estado comparado é o que ele acabou de
gravar. O predicado é sempre verdadeiro, e o dublê nunca devolve `false` por conta própria.

Antes de `b1f9cd1` o fake era a guarda honesta: chamava `video.marcaComoIniciada()` e devolvia o
resultado, sob o comentário "a guarda do WHERE aqui e a propria entidade", removido no mesmo
commit.

O que sobrou é o flag manual `proximaTransicaoMudaLinha`, usado em **um** teste do repositório
inteiro (`ProcessarExtracaoFalhouUseCaseTest:84`,
`compareAndSwapQuePerdeACorridaNaoPublicaVideoFalhou`). Os caminhos de corrida perdida de
`iniciada` e `concluida` ficaram sem cobertura nenhuma.

Isso importa porque é exatamente a guarda que o [ADR 0001](../../adr/0001-politica-de-falhas.md)
confia à camada de teste: "só a transição que de fato mudou a linha publica" é o que garante um
e-mail só por Vídeo. A suíte unitária deixou de provar isso. O `UPDATE` condicional real contra
Postgres continua correto — o defeito é do dublê, não da produção.

Vale notar de passagem que `proximaTransicaoMudaLinha` é campo mutável num dublê compartilhado
por seis suítes e não diz *qual* transição perde a corrida; se o consertando mantiver um flag,
vale nomeá-lo pela transição.

## 2. Javadoc de `ProcessarExtracaoFalhouUseCase` descreve a API antiga

Em `ProcessarExtracaoFalhouUseCase.java:11-15`, duas frases envelheceram:

- "`marcarFalha` so devolve o Vídeo preenchido quando **esta** chamada de fato tirou a linha de
  PROCESSANDO — reentregas do mesmo evento ... encontram o Optional vazio": não há mais
  `Optional<Video>`, o método devolve `CompletableFuture<Boolean>`.
- "PROCESSANDO -> FALHOU" no cabeçalho, enquanto o
  [ADR 0002](../../adr/0002-maquina-de-estados-em-duas-camadas.md) fixa o predecessor como
  conjunto, `RECEBIDO ou PROCESSANDO`. O javadoc irmão de `ProcessarExtracaoConcluidaUseCase`
  foi corrigido para `RECEBIDO/PROCESSANDO` no mesmo diff; este ficou para trás.

## Condição de aceite

- Os três dublês de guarda em `GatewaysEmMemoria.Videos` reprovam de verdade uma transição que
  não muda a linha, sem depender de um flag armado à mão pelo teste.
- Existe cobertura de corrida perdida para as três transições, não só para `falha`; a de
  `falha` continua provando a unicidade do e-mail do ADR 0001.
- O javadoc de `ProcessarExtracaoFalhouUseCase` descreve o retorno `Boolean` e o predecessor
  como conjunto, concordando com os ADRs 0001 e 0002.
- Suíte completa do serviço `videos` verde.

## Fora de escopo

Os cheiros de julgamento do mesmo review, que não são defeito e pedem decisão própria: a
duplicação da forma `buscarPorId -> marcaComoX -> marcarX` nos três consumidores (um
`TransicionarVideo` ao lado dos publicadores fecharia o par) e o data clump
`(concluidaEm, chavePacote, quantidadeFrames, tamanhoPacoteBytes)`.

## Resolução

O dublê voltou a ser guarda honesta, e por dentro em vez de por flag.

- `GatewaysEmMemoria.Videos.buscarPorId` devolve uma **cópia** da linha, como o `SELECT`
  devolve uma leitura. Sem isso o `marcaComoX` do use case movia o próprio objeto guardado
  no mapa, e a guarda chegava sem nada para julgar — a raiz do defeito.
- As três guardas viraram `transicionar(id, transicao)`, que aplica a transição do domínio à
  **linha armazenada** e devolve o que a entidade respondeu. É o `UPDATE ... where estado in
  predecessores()` do `VideoDataSourceAdapter` em memória, e o comentário "a guarda do WHERE
  aqui é a própria entidade", removido em `b1f9cd1`, volta a valer.
- `proximaTransicaoMudaLinha` saiu. A corrida perdida se arma por
  `outraEntregaVenceACorridaPara(id, destino)`: a linha daquele id se move uma vez, no
  instante da leitura — que é onde a corrida acontece de verdade, entre o `SELECT` e o
  `UPDATE`. Continua sendo estado mutável num dublê compartilhado, mas é por id, de disparo
  único, e nomeia a transição que vence, como o ticket pedia.
- Cobertura de corrida perdida nas três transições: `iniciada` (uma terminal venceu, a linha
  fica onde estava), `concluida` (a falha venceu, a linha fica `FALHOU` e sem chave de
  Pacote) e `falha` (nenhum `VideoFalhou` publicado, nada marcado como publicado — a
  unicidade do e-mail do ADR 0001, agora sem flag). Como os dois primeiros use cases
  descartam o booleano, os testes também afirmam o veredito da guarda: linha parada sozinha
  não distingue um `UPDATE` que reprovou de um que mentiu.
- Javadoc de `ProcessarExtracaoFalhouUseCase`: retorno `Boolean` em vez de `Optional<Video>`,
  e predecessor `RECEBIDO/PROCESSANDO` (ADR 0002), igual ao irmão da concluída.

Nada da seção "Fora de escopo" foi tocado.

### Validação

- Mutação deliberada: com `transicionar` devolvendo `true` sempre, os três testes de corrida
  reprovam. Antes desta correção, nenhum deles reprovava.
- Suíte completa na raiz: `./mvnw test -Dquarkus.http.test-port=8091`, 394 testes
  (107 videos, 263 extracao, 24 notificacao), zero falhas, erros ou skips. A porta alternativa
  é ambiental: o Keycloak do Compose que estava de pé ocupa a 8081 do `@QuarkusTest`.
- Ruído conhecido, não defeito: como o dublê usa `Video.marcaComoX`, a rejeição entre
  terminais emite o `WARNING` "Transição concorrente entre terminais ignorada" a partir do
  teste. No caminho real quem reprova é o `WHERE`, que não loga; os conjuntos de
  `transitaPara` e de `predecessores()` coincidem nas três transições.
