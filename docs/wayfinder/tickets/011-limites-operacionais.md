# Limites operacionais: tamanho, duração, formatos e retenção

- id: 011
- label: wayfinder:grilling
- status: fechado
- assignee: vandrep
- bloqueado-por:

## Question

Duas pesquisas convergiram no mesmo buraco. A de MinIO deixou cinco pontos em aberto na §6;
a de ffmpeg mediu que **fps=1 numa hora de 720p gera 3600 frames, entre 0,65 GB e 4,4 GB de
PNG**. Sem limites explícitos, um único upload derruba a demo — e é justamente o cenário que
um avaliador curioso vai testar.

A decidir:

- **Teto de upload**: qual valor para `quarkus.http.limits.max-body-size` (default de 10 MB
  é baixo demais, ilimitado é imprudente)? E o que o usuário recebe ao estourar?
- **Teto de duração ou de frames**: rejeitar na borda por duração via `ffprobe`, ou deixar
  processar e limitar o número de frames extraídos? Rejeitar cedo é honesto; limitar
  silenciosamente entrega um Pacote incompleto sem o usuário saber.
- **Formatos aceitos**: o original aceitava mp4/avi/mov/mkv/wmv/flv/webm por extensão do
  nome — o que não é validação. Validar por content-type, por `ffprobe`, ou aceitar tudo e
  deixar o exit 183 classificar como falha permanente?
- **Volume para o `uploads-directory`** do Vert.x: onde vive, quanto cabe, quem limpa.
- **Formato da chave de objeto no MinIO**: como identificar vídeo e Pacote, e se a chave
  carrega o dono (o que a torna adivinhável se algum dia houver presigned URL).
- **Retenção**: por quanto tempo o vídeo original e o Pacote sobrevivem, e quem apaga. O
  original apagava o vídeo após sucesso — mantemos isso?
- **Endpoint público do presigner**, se a decisão do contrato HTTP for por presigned URL.

Estes limites viram configuração e mensagens de erro, então alimentam diretamente o contrato
HTTP e a implementação do `extracao`.

## Resolução

Oito decisões em três rodadas de conversa; nenhuma pesquisa nova foi necessária — as
pesquisas 005 e 006 já tinham posto os fatos na mesa, e o que faltava era escolher números
dentro das caixas que o contrato HTTP (ticket 008) já havia desenhado.

**O último bullet da pergunta morreu antes da conversa**: "endpoint público do presigner"
pressupunha que o contrato HTTP escolhesse presigned URL, e o ticket 008 escolheu **stream**.

### 1. Teto de upload: 200 MB

`quarkus.http.limits.max-body-size=200M`, contra a sugestão de 1 GB da pesquisa 005.

O motivo de descer o número não é a borda, é o que vem depois: **bytes não limitam frames**.
Um mp4 bem comprimido de 1 GB pode ter três horas, e a medição do ticket 006 diz que uma
hora de 720p a 1 fps já produz 3600 frames e até 4,4 GB de PNG. O teto de bytes é guarda
**grossa**; a guarda fina é a duração (§5).

O `413` continua sendo cortado pelo Vert.x antes do JAX-RS e **não** sai como
`problem+json` — assumido no ticket 008, inalterado aqui.

### 2. Formato: a borda pergunta, o `extracao` responde

A borda valida **extensão** (`mp4`, `avi`, `mov`, `mkv`, `webm`) **e** content-type
começando com `video/`, devolvendo `415`. A autoridade real é o `extracao`, via exit code.

A decisão não foi sobre rigor de validação, foi sobre **fronteira de serviço**: `ffprobe`
na borda significa instalar ffmpeg na imagem do `videos` — 467 MB (ticket 006) e um serviço
que passa a conhecer codecs, contra o desacoplamento que o contrato de mensagens sustenta.
Também poria um processo externo no caminho síncrono, antes do `202`.

A validação da borda é honesta sobre o que é: uma pergunta "você quis mesmo mandar isso?",
não uma prova. `ARQUIVO_INVALIDO` e `FORMATO_NAO_SUPORTADO` já existem no contrato 007
exatamente porque a prova mora no `extracao`.

**Consequência que definiu o resto do ticket**: sem `ffprobe` na borda, a duração não é
conhecível antes do `202`.

### 3. Dois buckets, chave sem dono

| Bucket | Chave |
|---|---|
| `videos` | `{idVideo}/original.{ext}` |
| `pacotes` | `{idVideo}.zip` |

**Dois buckets, não um com prefixos**, porque a retenção (§4) é declarada por bucket no
MinIO: regras diferentes para original e Pacote saem de graça.

**A chave não carrega o dono.** A autoridade sobre propriedade está em `video.dono_sub`, no
Postgres; repeti-la na chave criaria uma segunda fonte de verdade sobre quem é dono de quê.
Com presigned URL fora (ticket 008), a adivinhabilidade da chave também deixou de importar.

**A extensão original fica na chave**: o `extracao` baixa para arquivo temporário e alguns
demuxers do ffmpeg se apoiam nela.

Quem constrói a chave é o `ArquivoGateway` do `videos` (ticket 009); o `extracao` recebe
origem e destino prontos e não conhece esta convenção (contrato 007). Mudar este formato
não toca nenhum contrato — só o adapter e o seed do MinIO.

### 4. Retenção: ciclo de vida do MinIO, 7 dias, zero código

O `main.go` original apagava o vídeo após o sucesso. **Não mantemos**: seria código de
exclusão no caminho feliz, e perder o original é perder a única forma de diagnosticar um
Pacote suspeito num sistema sem reprocessamento e sem `DELETE`.

Regra de ciclo de vida de 7 dias nos dois buckets, aplicada no seed do Compose. Uma linha de
configuração no lugar de uma classe, e responde à pergunta do avaliador sobre o disco.
Efeito colateral aceito: `video.chave_video` vira referência que pode expirar — ninguém a lê
depois do `CONCLUIDO`.

### 5. Teto de duração: 20 minutos, no `extracao`, falha permanente

Três caminhos estavam na mesa:

- **Nenhum teto** — os 200 MB como única guarda. É o cenário que derruba a demo.
- **Teto de frames, silencioso** — entrega Pacote incompleto sem o usuário saber. Um
  `CONCLUIDO` mentiroso é pior que um `FALHOU` honesto; é a mesma classe de problema que o
  ticket 006 gastou o `-xerror` para evitar.
- **Teto de duração, falha permanente** — escolhido.

O `ffprobe` **já roda** no `extracao` (o ticket 006 o usa para conferir a contagem de frames
contra a duração), então a duração é gratuita: nenhum processo novo, nenhuma dependência
nova. Acima de 20 minutos, falha **permanente** — ack imediato, sem gastar as três entregas
do `x-delivery-limit`.

Vinte minutos são 1200 frames a 1 fps, ~1,5 GB de PNG no pior caso.

**Código novo: `DURACAO_EXCEDIDA`**, o quinto publicável. É aditivo por construção — o
`DESCONHECIDO` do ticket 009 existe para que um código novo não derrube consumidor antigo.
Propagado para `docs/contratos/mensagens.md`, `docs/contratos/http-videos.md` e
`docker/postgres/init.sql`.

**Preço explícito**: o usuário só descobre depois do `202`, por e-mail. Não há alternativa
sem pôr ffmpeg no `videos` — é o custo direto da decisão §2, e foi aceito de olhos abertos.

### 6. `uploads-directory` do `videos`

Volume nomeado `fiapx-uploads` em `/var/fiapx/uploads`, com
`quarkus.http.body.uploads-directory` apontado explicitamente. Limpeza é o default
(`delete-uploaded-files-on-end=true`, pesquisa 005).

Contra a camada gravável do contêiner: 200 MB por upload atravessando o overlay filesystem é
lento, e o espaço só volta quando o contêiner é recriado. Contra `tmpfs`: é RAM, e dois
uploads concorrentes são 400 MB tirados de um laptop que já roda Postgres, RabbitMQ, MinIO,
Keycloak, MailHog e três serviços.

Configuração **explícita** porque um diretório temporário implícito de 200 MB é o tipo de
coisa que ninguém encontra quando o disco enche.

### 7. Scratch do `extracao`: volume nomeado, limpeza em duas camadas

Volume nomeado `fiapx-extracao-scratch` em `/var/fiapx/extracao`; diretório de trabalho por
vídeo em `/var/fiapx/extracao/{idVideo}`.

Aqui o worker **morre no meio por desenho** — o `CONTEXT.md` é explícito que worker morto
gasta uma tentativa — então "o processo termina e limpa" é justamente a suposição que não
vale. Duas camadas:

1. **`finally` por mensagem** — apaga o diretório ao fim de cada tentativa, com sucesso ou
   falha. Cobre o caso normal.
2. **Varredura no boot** — apaga tudo sob a raiz do scratch ao subir. Cobre o órfão de
   crash, que aqui é rotina, não exceção. É seguro porque `prefetch=1` (contrato 007):
   nenhum outro trabalho está em voo enquanto o worker inicia.

Limpeza periódica foi rejeitada — máquina nova para um problema que as duas camadas cobrem.

O diretório é **apagado-e-recriado** no início de cada tentativa, não reaproveitado: com
`failure-strategy=requeue`, a mesma mensagem volta, e a tentativa nova não pode herdar
frames meio-escritos da anterior.

### 8. Orçamento de disco do `extracao`: 4 GB, frames em disco

Pico por Extração: vídeo baixado (≤200 MB) + 1200 frames PNG (~1,5 GB) + ZIP `STORED`, que
por não comprimir pesa o mesmo que os frames (~1,5 GB) ≈ **3,2 GB**. É pico por worker, não
multiplicado: `prefetch=1`.

Dá para quase dividir por dois com `image2pipe` alimentando o `ZipOutputStream` direto, sem
os frames pousarem em disco. **O 011 não manda no formato** — isso é decisão do ticket 015.
O que o 011 fixa é o número que o Compose e a documentação carregam, e ele é orçado no
caminho conservador.

O pipe fica registrado como otimização disponível, com a ressalva de que `image2pipe` com PNG
entrega uma sequência concatenada num stream só, que alguém precisa fatiar por assinatura de
arquivo — e briga com as duas coisas que o ticket 006 fixou como obrigatórias (classificação
por exit code e conferência da contagem de frames contra a duração).

Estourar o orçamento já tem tratamento: `ENOSPC` é exit 228, **transitório** pelo ticket 006.
Vira retry, não Pacote corrompido.

### Sem ADR

Nenhuma das oito passa nos três testes do `domain-modeling`: são configuração e são baratas
de reverter — mudar 200 MB para 1 GB é uma property. O único candidato era o teto de duração
no `extracao` em vez de na borda, mas o raciocínio dele é consequência direta da fronteira de
serviço já registrada nos tickets 006 e 007, e cabe nesta resolução.

O `CONTEXT.md` também não muda: `DURACAO_EXCEDIDA` é um código, e o glossário
deliberadamente não enumera códigos — só afirma que motivo é código, nunca frase.

### Requisitos duros que saem daqui para o Compose

- Volumes nomeados `fiapx-uploads` (em `videos`, `/var/fiapx/uploads`) e
  `fiapx-extracao-scratch` (em `extracao`, `/var/fiapx/extracao`), este com folga de **4 GB**.
- Seed do MinIO cria **dois** buckets, `videos` e `pacotes`.
- Regra de ciclo de vida de **7 dias** nos dois buckets.
