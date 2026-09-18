# Diagramas e narração coerentes com o código depois da série do Vídeo perdido

- id: 111
- label: ready-for-agent
- status: fechado
- assignee: claude (sessão de 2026-09-14, SHA inicial b4c98bd)
- bloqueado-por: 105, 106, 107, 108
- prioridade: P3

## Origem

Revisão do [ticket 104](104-aceite-do-video-no-commit-da-linha.md), em 2026-09-13, sobre
`develop @ 7dd9065`. A revisão achou documentos que descrevem um sistema anterior ao código, e o
104 deixou esses pontos de fora de propósito. O mantenedor decidiu em 2026-09-14: um ticket só,
escopo restrito, e **bloqueado pelos 105–108**. Os quatro mudam o que os mesmos documentos
descrevem, e a correção deve acontecer numa passada só, depois deles.

## O que está desatualizado

Conferido contra o código em 2026-09-14:

1. **Diagrama do caminho feliz** (`docs/arquitetura.md`). O `202` aparece antes do `ExtrairVideo`
   e da marca. Desde o 104, a resposta sai depois do publish confirmado ou depois do teto de 2 s, e
   a marca pode chegar depois da resposta. O parágrafo logo abaixo cita "passo 4" e "passo 5"
   desse diagrama.
2. **Diagrama do caminho de falha e o texto que o acompanha** (`docs/arquitetura.md`). O diagrama
   mostra `UPDATE ... WHERE id = ? AND estado = 'PROCESSANDO'`, e o texto diz que "`FALHOU` só é
   alcançável a partir de `PROCESSANDO`" e cita `EstadoVideo.predecessor()`. Isso está velho desde
   o [ticket 027](027-melhorias-medidas.md): os estados terminais aceitam `RECEBIDO` **ou**
   `PROCESSANDO` ([ADR 0002](../../adr/0002-maquina-de-estados-em-duas-camadas.md)), e o método é
   `predecessores()`.
3. **Contagem de testes.** O `docs/arquitetura.md` diz "130 testes, 96 sem container". O
   `docs/roteiro-video.md` diz "cento e trinta" em duas tomadas, e numa delas também "noventa e
   seis". O mapa diz "144 (103 sem Docker)". A suíte da raiz tinha 470 testes em 2026-09-13.
4. **Narração do roteiro** (`docs/roteiro-video.md`). A tomada do passo 3 afirma que, no `202`, "o
   comando já está na fila". A tomada da sequência do caminho feliz cita "passo quatro" e "passo
   cinco" do diagrama do item 1.

Quando este ticket for pego, os 105–108 já terão mudado o sistema. Os dois diagramas e os trechos
acima devem bater com o código **daquele momento**, e não com esta lista. Se algum dos quatro já
tiver corrigido um item, basta registrar isso.

## Fora do escopo

- Varredura geral de coerência dos documentos. Só entram os dois diagramas, o texto em volta
  deles, as três contagens de testes e as tomadas do roteiro citadas acima.
- As limitações da conservação e a linha "Não perder requisição em pico", que são do
  [ticket 109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md). O 109 edita o mesmo
  `docs/arquitetura.md` em outra seção: parta do arquivo como ele estiver.
- Os três outros diagramas do `docs/arquitetura.md`.

## A restrição do roteiro

O roteiro é narração dublada com orçamento de **palavras**, não de segundos: ~145 palavras por
minuto, com teto por bloco escrito no cabeçalho de cada um ([ticket 024](024-roteiro-video.md)).
Corrigir uma frase não pode estourar o bloco. Se renumerar o diagrama mudar a narração, prefira
reescrever a frase para não depender do número do passo. Se não couber no bloco, pare e peça
decisão ao mantenedor, em vez de cortar outra tomada por conta própria.

## Critérios de aceite

- [ ] O diagrama do caminho feliz mostra a ordem real entre `INSERT`, publish, marca e `202`,
      incluindo o que acontece quando o publish não confirma dentro do teto.
- [ ] O diagrama do caminho de falha e o texto em volta mostram os predecessores reais do
      terminal e nomeiam o método que existe.
- [ ] Todo "passo N" citado em texto ou narração existe no diagrama correspondente.
- [ ] As três contagens de testes vêm de uma execução nova de `./mvnw test` a partir da raiz,
      com a data da execução registrada na resolução.
- [ ] O roteiro não afirma que o comando está na fila no `202`.
- [ ] A contagem de palavras de cada bloco alterado do roteiro continua dentro do teto do bloco, e
      os números do cabeçalho do bloco são atualizados.
- [ ] Linha em "Decisões até aqui" no mapa.

## Resolução

Escrito em 2026-09-14 sobre `develop @ b4c98bd`. Só documentação. Nenhum dos 105–108 tinha
corrigido algum dos quatro itens: nenhum deles tocou os diagramas nem o roteiro.

**Caminho feliz.** O diagrama mostra `INSERT` → nota do aceite → publish do `ExtrairVideo` e um
`alt`. Num ramo, o confirm e a marca chegam dentro do teto de 2 s, e só então sai o `202`. No
outro, o teto estourou ou o publish ou a marca falhou: o `202` sai com a marca nula, um `opt`
mostra o confirm tardio que ainda grava a marca, e uma nota diz que a varredura do ADR 0003
publica depois. O parágrafo de baixo deixou de citar número de passo: diz que o `202` sai depois
de tentar o publish e aponta para `docs/contratos/http-videos.md` § *O que o `202` promete*, que é
onde a regra mora, em vez de repeti-la. O `autonumber` ficou, e nenhum texto depende dele.

**Caminho de falha.** O `WHERE` virou `estado IN ('RECEBIDO', 'PROCESSANDO')`, o texto diz por que
são dois predecessores e nomeia `EstadoVideo.predecessores()`. O texto e uma nota do diagrama
separam os dois jeitos de a reentrega não mudar nada: a entidade recusa a transição antes do
`UPDATE` quando a linha já é terminal, e o predicado só decide quando duas entregas correm juntas.

**Além da letra, porque o diagrama tinha de bater com o código:**

- o motivo do `ExtracaoFalhou` que sai da DLQ era `ARQUIVO_INVALIDO` e virou
  `TENTATIVAS_ESGOTADAS`, que é o único que `ProcessarTentativasEsgotadasUseCase` publica.
  `ARQUIVO_INVALIDO` é falha permanente, publicada na primeira entrega e com ack. Uma nota no
  diagrama diz isso;
- os dois diagramas ganharam a tag `desfecho=sim` no original depois do `UPDATE` terminal, do
  ticket 105 (`TransicaoDeVideo.marcarDesfechoDoOriginal`);
- a linha "Testes que garantam a qualidade" da tabela de requisitos do `arquitetura.md` dizia
  144 (103) e foi atualizada junto, para o arquivo não ficar com duas contagens.

**Contagens.** `./mvnw test` na raiz em **2026-09-14, das 13:44 às 13:48**, `BUILD SUCCESS`:
**512 testes** (`videos` 193, `extracao` 290, `notificacao` 29). "Sem container" contou por
classe, pelos relatórios do surefire: são 94 testes em classes `@QuarkusTest` ou no runner do
Cucumber e **418** nas demais (`videos` 133, `extracao` 263, `notificacao` 22). Os 418 incluem os
testes do `extracao` que chamam o `ffmpeg` real, que não sobe container. Atualizados: o
`arquitetura.md` (a seção *Por dentro de um serviço* e a tabela de requisitos), as duas tomadas
do roteiro e a tabela de estado do mapa. A linha do 027 em "Decisões até aqui", que diz 144 (103),
é registro da época e ficou.

**Roteiro.** O passo 3 diz que o arquivo e o registro já estão duráveis e que o vídeo não se perde,
e não mais que o comando está na fila. A tomada do caminho feliz perdeu "passo quatro" e "passo
cinco": diz que o `videos` publica e responde quando o broker confirma, ou em dois segundos, e que
a varredura cobre o resto. As palavras foram contadas somando as palavras das linhas `>` de cada
bloco, o que reproduz os cabeçalhos. Os blocos alterados continuam com **393, 605 e 336**, iguais ao
que já estava escrito, então nenhum tempo do cabeçalho mudou.

**Revisão.** O `/code-review` contra `b4c98bd` trouxe três mudanças: o parágrafo do caminho
feliz passou a apontar para o contrato, o passo 3 fala do vídeo e não do comando, e ficaram
separados a recusa pela entidade e o predicado. Também trouxe o ramo da marca que falha dentro do
teto e a nota "publica depois", no lugar de "republica".

**Fora, para o mantenedor decidir:**

- a tomada dos passos 6 a 8 do roteiro diz que o ffmpeg prova que o arquivo não é vídeo "três
  entregas depois" e que o motivo é `ARQUIVO_INVALIDO`. Pelo código, a falha é permanente e sai na
  primeira entrega. Agora ela contradiz o diagrama, e isso precisa ser corrigido antes de gravar o
  vídeo;
- a tomada do caminho de falha ainda diz "o estado predecessor", no singular.
