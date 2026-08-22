# O que a API responde quando o Pacote já expirou

- id: 019
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Question

Descoberto ao resolver o ticket 018: a tabela `video` **nunca perde linhas**, mas o ticket 011
fixou ciclo de vida de 7 dias no MinIO. No oitavo dia existe um Vídeo `CONCLUIDO`, listado
como tal pela API, cujo `chave_pacote` aponta para um objeto que não existe mais. O download
quebra de um jeito que o contrato HTTP do ticket 008 não prevê: lá o `409` significa "Pacote
indisponível" no sentido de *ainda não pronto*, não de *não existe mais*.

A decidir:

- **O que o `GET /videos/{id}/pacote` responde?** Reusar o `409` com um código de motivo novo
  é o caminho barato e provavelmente certo; `410 Gone` é semanticamente mais preciso e custa
  uma entrada a mais no contrato. `404` está errado — o Vídeo existe e é do usuário.
- **A listagem mente?** Um Vídeo `CONCLUIDO` cujo Pacote evaporou continua dizendo
  `CONCLUIDO`. Ou (a) aceita-se, e a verdade só aparece no download; ou (b) a representação de
  Vídeo ganha algo que distinga "tem Pacote" de "teve Pacote"; ou (c) um estado novo, que é
  caro — mexeria no `ck_video_estado`, no grafo do `core` e no ADR 0002.
- **Quem descobre a expiração?** Ninguém varre o MinIO; a descoberta é preguiçosa, no momento
  do download, quando o `ArquivoGateway` não acha o objeto. Isso é diferente de todo o resto
  do sistema, onde o Postgres é a autoridade — aqui o Postgres está desatualizado e só o
  adapter sabe.
- **Ou a retenção do ticket 011 é que está errada?** 7 dias foi escolhido para o Pacote e para
  o original pelo mesmo número. Se o Pacote nunca expirasse, o problema não existiria — ao
  preço de disco que uma demo não paga.

Este ticket precisa fechar **antes** do 016 escrever o `BaixarPacoteUseCase`.

## Resolução

Quatro perguntas, duas rodadas, nenhuma pesquisa nova. A decisão cabe numa frase: **o Pacote
expira e a API não finge que sabe disso**. O estado do Vídeo continua respondendo só o que
aconteceu com a Extração, e a disponibilidade do arquivo é respondida no único lugar onde
alguém pergunta — o download.

### 1. A retenção do ticket 011 está certa: 7 dias nos dois buckets

O caminho barato era tirar a expiração do bucket `pacotes` e **dissolver** o ticket: sem
expiração, não há Vídeo `CONCLUIDO` apontando para objeto ausente, e a frase do glossário
segue literalmente verdadeira. Foi recusado, e não por disco — numa demo nem o original
(teto de 200 MB) nem o Pacote (~1,5 GB no pior caso do teto de 20 min) enchem nada.

Foi recusado porque compraria uma tranquilidade falsa. O objeto pode sumir por motivos que a
política de ciclo de vida não controla: volume do MinIO recriado sem o do Postgres — muito
plausível numa demo —, objeto removido à mão, bucket reprovisionado. O `BaixarPacoteUseCase`
precisa do ramo "o objeto não está lá" de qualquer jeito; retirar a expiração só o tornaria
um ramo **nunca exercitado**, que é pior que um ramo raro. Além disso, a regra de 7 dias é a
resposta pronta à pergunta da banca sobre crescimento de disco.

### 2. `410 Gone`, não um segundo sentido de `409`

O `409` de hoje significa *ainda não pronto*; este caso é o oposto, *acabou e não volta*. A
diferença é operacional, não estética: quem recebe `409` racionalmente **repete** a
requisição, porque a Extração pode terminar a qualquer momento; quem recebe `410` sabe que
insistir não adianta. Reusar o `409` obrigaria o cliente a separar os dois sentidos pelo
texto do `detail`, que não é contrato.

A terceira opção — `409` com um código de motivo novo no corpo — era a pior: criaria um
segundo vocabulário de códigos ao lado do enum `motivo`, que é de **falha de Extração**.
Expirar não é falhar: a Extração concluiu. `404` está errado nas três, porque o Vídeo existe
e é do usuário.

Fica `410`, `title: "Pacote expirado"`, e o prazo de 7 dias dito no `detail` e na descrição
do OpenAPI.

### 3. A listagem "mente", e está tudo bem

Recusados o estado novo (mexeria no `ck_video_estado`, no grafo do `core` e no ADR 0002 para
comunicar o que uma resposta HTTP já comunica) e o campo booleano observado (exigiria olhar o
MinIO a cada item da listagem).

Recusada também uma variante que a pergunta original não tinha considerado e que chegou perto
de ser aceita: um **instante calculado**, `pacoteDisponivelAte = finalizado_em + 7 dias`,
presente só em `CONCLUIDO`. Ele não custa consulta ao MinIO nem coluna nova, e é
*conservador por construção* — o ciclo de vida do MinIO é avaliado por dia, com o objeto
elegível só depois da meia-noite UTC seguinte ao sétimo dia e removido quando o scanner
passa, então o MinIO apaga **sempre depois**, nunca antes, do instante calculado.

Perdeu por autoridade: quem apaga é o MinIO, e o campo poria na borda pública um número que o
`videos` não controla. No dia em que os dois divergirem — policy alterada, scanner atrasado —
a API estará mentindo com precisão de segundos, o que é pior que a omissão de hoje. A mesma
informação vai para a descrição do OpenAPI e o `detail` do `410`, sem fingir ser dado da linha.

O que sustenta o "está tudo bem" é a leitura do estado: `estado` responde *o que aconteceu
com a Extração*, não *o que ainda está guardado*. `CONCLUIDO` é verdade para sempre.

### 4. Descoberta preguiçosa, e o `GET` não escreve

Ninguém varre o MinIO. A ausência é descoberta no download, pelo `ArquivoGateway`, e o
`videos` **não grava nada** ao descobri-la — nem anula `chave_pacote`, nem cria
`pacote_expirado_em`.

Com a decisão 3, o write-back gravaria dado que ninguém lê. Mas ele seria recusado mesmo se
a listagem precisasse saber: a tabela `video` é o registro do que **aconteceu**, não um
espelho do bucket, e o ADR 0003 acabou de investir em mantê-la como fonte de verdade de
publicação. Um `GET` que escreve também abriria transação e caminho de concorrência na única
leitura hoje trivialmente concorrente — para economizar a chamada mais barata que existe, um
`NoSuchKey`.

### Sem ADR

A escolha é reversível (uma linha na tabela de erros), e o "por quê" cabe inteiro no contrato
HTTP, que é onde quem mexe no endpoint já vai olhar. Um ADR aqui seria indireção sem
trade-off durável.

### O que muda

- [`docs/contratos/http-videos.md`](../../contratos/http-videos.md) — `410 Gone` /
  `Pacote expirado` na tabela de erros, e a seção "Quando o Pacote não existe" reescrita com
  os dois sentidos separados.
- [`CONTEXT.md`](../../../CONTEXT.md) — "tem exatamente um Pacote" virou "**produziu**
  exatamente um Pacote", e o Pacote ganhou prazo no glossário. A frase antiga ficava falsa no
  oitavo dia.

### Herda o ticket 016

Duas notas para quem escrever o `BaixarPacoteUseCase`:

1. O adapter precisa distinguir `NoSuchKey` de erro genérico do S3. Mapear qualquer falha do
   MinIO para `410` faria um MinIO fora do ar mandar o cliente desistir para sempre — o
   oposto do que a decisão 2 quis comprar. Erro de infraestrutura é `500`.
2. O cenário BDD do `410` exige apagar o objeto do bucket pelo step, não esperar sete dias.
