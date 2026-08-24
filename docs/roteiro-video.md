# Roteiro do vídeo de apresentação

Vídeo de **no máximo 10 minutos** apresentando documentação, arquitetura e o projeto
funcionando — o entregável de apresentação do Hackathon. Este arquivo é a narração integral,
palavra a palavra, com as tomadas que acompanham cada trecho.

**Alvo: 9:00, não 10:00.** A folga é o que impede a entrega de ser reprovada por quinze
segundos. Esta versão fecha em **9:19**, com 41 segundos de margem.

## Como este roteiro é usado

A produção é em três passos, nesta ordem:

1. **Gravar o vídeo** — as tomadas abaixo, sem áudio.
2. **Editar** — acelerar as esperas, encaixar os cortes entre blocos.
3. **Dublar** — ler esta narração por cima do vídeo pronto.

Por isso a unidade de orçamento é a **palavra**, não o segundo: o teto de dez minutos é do
áudio, e o vídeo se ajusta a ele. Cada bloco declara sua contagem. Em ritmo natural de
locução em português, ~145 palavras por minuto.

| Bloco | Entra em | Duração | Palavras |
|---|---|---|---|
| 1. Abertura | 0:00 | 0:29 | 70 |
| 2. Funcionando | 0:29 | 2:28 | 357 |
| 3. Arquitetura | 2:57 | 4:10 | 605 |
| 4. Fechamento | 7:07 | 2:12 | 320 |
| **Total** | | **9:19** | **1.352** |

Os números acima são medidos, não estimados: são as palavras que estão de fato escritas
abaixo. Se você editar a narração, remeça — o teto de dez minutos não perdoa.

### Regras de edição

- **Esperas são aceleradas com a marca `4×` na tela, nunca cortadas.** Acelerar preserva a
  continuidade: o avaliador vê que nada foi retirado do meio da prova. Um corte dentro de uma
  verificação levanta exatamente a dúvida que a demonstração existe para fechar. E acelerar
  sem dizer é a mesma coisa que cortar — daí a marca.
- **O `docker compose up` fica fora do vídeo.** É o único trecho longo que não demonstra
  nada; vira uma frase de narração.
- **Tudo que aparece na tela é artefato versionado** — diagrama, terminal, CI. Não há slide.
  Isso é, em si, um argumento sobre a documentação.

### O que não aparece

Código linha a linha, Swagger clicado à mão, console do RabbitMQ, console do MinIO. São os
quatro candidatos óbvios a passeio pelo repositório, e nenhum prova nada que os diagramas e o
`smoke.sh` não provem melhor.

### Preparo antes de gravar

- [ ] Rodar `scripts/smoke.sh` uma vez e **deixar a stack de pé** — aquece as JVMs e as
      imagens, de modo que a tomada real não filme o primeiro acesso.
- [ ] Renderizar os cinco diagramas de [`docs/arquitetura.md`](arquitetura.md) em tela cheia
      com `mermaid-cli` (o mesmo caminho usado para conferi-los no ticket 023).
- [ ] Terminal com fonte grande — o que se lê num monitor não se lê num vídeo.
- [ ] Para a tomada da árvore de módulos, filmar **o que está versionado**
      (`git ls-files | grep videos/src/main`), não um `tree` da cópia local: o
      `init-project.sh` deixou diretórios `com/example/` vazios que o git não rastreia e que
      ninguém que clonar o repositório enxerga.

---

## Bloco 1 — Abertura (0:00–0:29, 70 palavras)

**Tela:** diagrama de **Contexto** em tela cheia. Opcionalmente, 3 segundos do `main.go` do
projeto original antes dele — como *antes*, não como leitura de código.

> O projeto base da FIAP X processava vídeo num único processo Go, de forma síncrona: quem
> enviava ficava com a conexão aberta até o ffmpeg terminar, e uma falha no meio não deixava
> rastro. Esta versão faz o mesmo trabalho com três serviços Quarkus que só conversam por
> mensagem. Nos próximos minutos eu mostro o sistema funcionando, a arquitetura que o
> sustenta, e onde cada requisito do enunciado foi parar.

---

## Bloco 2 — O projeto funcionando (0:29–2:57, 357 palavras)

Um único take do `scripts/smoke.sh`, do passo 2 ao 9. Os passos 0 e 1 (dependências e
Compose) não entram.

### Tomada — o script começa

> O que você vai ver é o `scripts/smoke.sh`, versionado no repositório. Não é uma sequência
> de comandos que eu digito na hora: é a verificação ponta a ponta do projeto, que qualquer
> pessoa roda com um comando só. A stack já está de pé; do zero, ela sobe e o smoke completa
> em um minuto e oito segundos. As esperas estão aceleradas quatro vezes, e nada foi cortado.

### Tomada — passo 2, token no Keycloak

> Primeiro, um token no Keycloak. O sistema não tem cadastro e não guarda senha: a identidade
> vem inteira do token. E antes de seguir, o script confirma que a borda recusa quem não o
> apresenta.

### Tomada — passo 3, envio do vídeo

> O envio responde **202 Accepted**, com o `Location` do recurso. Repare que ele responde
> antes de qualquer trabalho de vídeo existir: o arquivo já está durável e o comando já está
> na fila, mas nenhum frame foi extraído. Esse 202 é a peça central do desenho.

### Tomada — passo 4, processamento assíncrono *(acelerar 4×)*

> Como o usuário já foi embora, a listagem de status é o único canal pelo qual ele descobre o
> desfecho — é por isso que ela é requisito, e não luxo. O script pergunta em intervalos, e os
> estados aparecem na ordem: RECEBIDO, PROCESSANDO, CONCLUÍDO.

### Tomada — passo 5, download do pacote

> O download vem por stream, e o `unzip -t` prova que o ZIP chegou inteiro. Dentro dele, os
> frames: um por segundo de vídeo.

### Tomada — passos 6 a 8, caminho de falha *(acelerar 4×)*

> Agora o caminho de falha, que é o que o enunciado pede ao falar em notificar o usuário. O
> script envia um arquivo que não é vídeo, com extensão mentirosa. A borda aceita — ela valida
> extensão e content-type, não conteúdo —, e quem prova que aquilo não é vídeo é o ffmpeg,
> três entregas depois. O vídeo termina em FALHOU com o motivo `ARQUIVO_INVALIDO`, o pedido do
> pacote responde 409, e o e-mail chega ao MailHog. O script não conta e-mails: ele procura o
> identificador deste vídeo dentro do corpo, porque a caixa de entrada sobrevive entre
> execuções e contar daria falso verde.

### Tomada — passo 9, propriedade do vídeo

> Por último, um segundo usuário. O vídeo do primeiro não aparece na listagem dele, e o acesso
> direto pelo identificador responde 404 — não 403, porque a existência de um recurso alheio
> também é informação.

---

## Bloco 3 — Arquitetura (2:57–7:07, 605 palavras)

Cinco diagramas, todos de [`docs/arquitetura.md`](arquitetura.md), em tela cheia.

### Tomada — diagrama de Contexto (19s)

> O sistema por fora. O usuário se autentica no Keycloak, fala HTTP com o sistema levando o
> token, e o sistema avisa uma falha por SMTP. O dono de um Vídeo é o `sub` do token de quem o
> enviou, nunca um campo do request.

### Tomada — diagrama de Containers (55s)

> Por dentro, três serviços de negócio e a infraestrutura. O `videos` é a borda pública e o
> único dono do estado; `extracao` e `notificacao` são workers sem banco.
>
> Três coisas nesse desenho não são acidentais. **Primeira:** nenhuma seta liga `extracao` a
> `notificacao`. O worker que descobre a falha não é quem avisa o usuário — toda falha volta
> para o `videos`, e é essa volta que impede o e-mail duplicado. **Segunda:** o conteúdo do
> vídeo nunca entra numa mensagem. O que circula pelo RabbitMQ é a chave do objeto no MinIO;
> um arquivo de duzentos megabytes numa fila seria um problema de memória do broker, não de
> arquitetura. **Terceira:** o `extracao` não guarda progresso — recebe as chaves prontas,
> trabalha e relata. É exatamente isso que permite matá-lo e subir outro no lugar.

### Tomada — diagrama Por dentro de um serviço (53s)

Encaixar aqui, por ~10s, a árvore de pacotes versionada do módulo `videos`.

> Este desenho é o mesmo nos três serviços, e é onde a Clean Architecture aparece — porque no
> diagrama anterior cada serviço é uma caixa opaca. As setas cheias apontam para dentro. O
> `core` é Java puro: entidades, casos de uso e as interfaces dos gateways. Ele não importa
> JAX-RS, CDI, Hibernate nem Mutiny; fala `CompletableFuture`, não `Uni`, justamente para não
> conhecer o reativo do Quarkus. Tecnologia só existe em `framework`.
>
> E isso não é aspiração documentada. O `ArchitectureConstraintsTest` verifica o layout dos
> pacotes, os imports proibidos e onde cada anotação pode aparecer; ele roda no `verify` e
> reprova o build. O ganho é medível nos testes: o `core` inteiro roda com dublês em memória.
> Dos cento e trinta testes do projeto, noventa e seis não sobem container nenhum.

### Tomada — sequência do caminho feliz (50s)

> O caminho feliz, em ordem. O `videos` grava o arquivo no MinIO, insere a linha no Postgres
> em RECEBIDO, e só então responde 202 — passo quatro. Do passo cinco em diante o usuário já
> não está mais lá. O comando `ExtrairVideo` vai para a fila; o `extracao` pega, anuncia que
> começou, baixa o vídeo, valida a duração com `ffprobe`, extrai um frame por segundo com
> ffmpeg, empacota e grava o pacote de volta. Cada anúncio dele volta ao `videos`, que é quem
> move o estado — os workers relatam, o dono do estado decide. O download, no fim, é stream de
> ponta a ponta. Presigned URL foi recusada: é um bearer token na query string, e o host entra
> na assinatura.

### Tomada — sequência do caminho de falha (73s)

> O caminho de falha carrega as duas garantias que uma demonstração não consegue mostrar.
>
> A primeira é não perder trabalho. A fila é **quorum** — replicada e durável —, com ack
> manual e limite de três entregas. Uma tentativa é uma entrega, não um erro: se o worker
> morre no meio, aquela tentativa foi gasta. É deliberado — assim um arquivo que derruba o
> worker esgota as tentativas e falha, em vez de derrubar o worker para sempre. E esgotado o
> limite a mensagem não some: vai para a dead-letter queue, que o próprio `extracao` consome e
> transforma em `ExtracaoFalhou`.
>
> A segunda é a unicidade do e-mail — e ela não está no `notificacao`, que não guarda estado
> nenhum. Quem impede a notificação de se multiplicar é este `UPDATE`, que exige no `WHERE` o
> estado predecessor. Só a primeira entrega muda a linha, e só a transição que mudou a linha
> publica `VideoFalhou`. A segunda e a terceira dão ack em silêncio. É isso que dispensa banco
> no `notificacao` e torna todo consumo de evento idempotente.

---

## Bloco 4 — Fechamento (7:07–9:19, 320 palavras)

### Tomada — tabela *Requisitos do enunciado* (53s)

> Os requisitos do enunciado, um a um, e onde cada um está resolvido. Dois deles merecem a
> frase que a tela não dá.
>
> **Não perder requisição em pico.** Além do 202 e da fila durável, há uma varredura a cada
> trinta segundos que republica o que ficou para trás. Gravar no Postgres e publicar no
> RabbitMQ não é uma operação atômica: um crash entre as duas deixaria um Vídeo eternamente em
> RECEBIDO — que, para o usuário, é exatamente a requisição perdida que o enunciado proíbe.
>
> **Escalar.** O gargalo real é o `extracao`, que é sem estado e pega uma extração por vez:
> dobrar réplicas dobra a vazão. É também por isso que as imagens são publicadas para amd64 e
> arm64 — ffmpeg emulado inviabilizaria o serviço.

### Tomada — run verde do GitHub Actions (25s)

> Qualidade e CI/CD na mesma tela. Cada push roda `verify` na raiz, e a `main` é protegida:
> só entra por pull request. São cento e trinta testes — unitários do `core` com dublês,
> Cucumber pela borda HTTP, o teste arquitetural, e ffmpeg de verdade contra um vídeo real no
> `extracao`. Passando, o mesmo pipeline publica as três imagens multi-arquitetura no GHCR.

### Tomada — árvore de `docs/` (24s)

> A documentação está toda no repositório: três ADRs, dois contratos, o desenho de
> arquitetura, e o mapa com os vinte e quatro tickets que produziram cada decisão — com as
> alternativas consideradas e o que foi medido em cada uma. JavaCV, outbox canônico,
> Kubernetes e módulo Maven compartilhado foram recusados, e o motivo de cada recusa está
> escrito.

### Tomada — seção *Limitações conhecidas* (24s)

> Duas coisas eu não defendo, apenas aceitei. A escalabilidade é **argumentada, não medida**:
> o desenho suporta réplicas, mas não há teste de carga que prove a linearidade. E o e-mail é
> **pelo menos uma vez** — numa janela estreita, o usuário pode receber o aviso duas vezes.
> Foi escolha consciente: duplicar um aviso é melhor que engolir uma falha.

### Tomada — README, encerramento (6s)

> O repositório é `vandrep/fiapx-v2`, público, e o `scripts/smoke.sh` reproduz tudo que você
> viu aqui. Obrigado.
