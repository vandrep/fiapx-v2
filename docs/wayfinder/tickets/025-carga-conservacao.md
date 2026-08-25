# Harness de carga e prova de conservação sob pico

- id: 025
- label: wayfinder:task
- status: fechado
- assignee: vandrep (sessao de 2026-08-25)
- bloqueado-por:

## Question

`docs/arquitetura.md` § *Limitações conhecidas* declara que **"a escalabilidade é argumentada,
não medida"**. Este ticket constrói o instrumento que fecha metade dessa frase e o usa para
julgar a afirmação que o enunciado cobra diretamente: *"em caso de picos, o sistema não deve
perder uma requisição"*.

"Resiliência" não é uma propriedade — são três, e elas pedem instrumentos diferentes.
**Conservação** (nenhum envio fica sem desfecho) é um invariante de correção, que um teste de
carga *falseia*; **linearidade** (dobrar réplicas dobra a vazão) é vazão, que se mede, e fica no
[026](026-linearidade-horizontal.md); **latência da borda** cai de graça do harness deste
ticket. Este resolve conservação e entrega o harness que o 026 reusa sem construir nada.

### O que construir

Tudo em `scripts/carga/`, mais um overlay `docker-compose.carga.yml` na raiz. O
`docker-compose.yml` principal **é a demo da banca** e não recebe `cpus:` nem `replicas:` que
existem só para experimento — overlay é a construção que o Compose oferece para isto.

| Peça | Papel |
|---|---|
| gerador de fixture | `ffmpeg -f lavfi -i testsrc` produz os vídeos de carga; **fora do git** (`.gitignore`), regenerados por quem rodar |
| injetor (k6) | `docker run grafana/k6` — nada instalado no host, multipart nativo, histograma de latência do `202` de graça |
| oráculo | censo em SQL + amostra pela API (ver abaixo) |
| orquestrador | bash, no espírito narrado do `smoke.sh` |

**Fixtures gerados, não versionados e não reaproveitados do teste.** Os 8 KB / 3 s de
`extracao/src/test/resources/fixtures/video-valido.mp4` são fixture de *correção*: com eles o
tempo de extração é comparável ao overhead de fila e MinIO. Aqui ele fica como **controle** —
é o que separa, na conta final, overhead fixo de extração real. Versionar um vídeo pesado no
repo está fora de questão; gerar é determinístico e barato.

**k6 injeta, bash julga.** O injetor mede o que o *cliente* vê; o oráculo mede o que o
*sistema* fez, e isso é polling contra Postgres e API — não é trabalho de ferramenta de carga.
Misturar os dois dentro do k6 produz o teste que ninguém relê.

**O denominador vem do injetor.** O k6 grava o `Location` de cada `202` num arquivo, e o censo
é conferido **contra essa lista**. Contar só o que está no banco responderia "o banco é
consistente consigo mesmo", que não é a pergunta.

**Oráculo em dois papéis.** `SELECT estado, count(*) ... GROUP BY estado` é o censo, porque um
oráculo que perturba o experimento não é oráculo — 500 Vídeos pela API paginada somariam
dezenas de requisições ao sistema sob medição. Mas a garantia do enunciado é sobre o que o
*usuário* recebe, e uma linha `CONCLUIDO` que o `GET /videos` não devolve é uma requisição
perdida do mesmo jeito: uma **amostra** de IDs sorteados, conferida pelo dono via API, fecha
essa brecha sem virar carga.

**Token renovado no injetor.** O `access_token` dura 5 min e a corrida os atravessa. Aumentar
`accessTokenLifespan` no `realm-export.json` está **fora de questão**: aquele arquivo é fonte
única — o mesmo que o Compose importa, que o Dev Services sobe em `@QuarkusTest` e que a banca
vê. Deformar o objeto medido para acomodar o instrumento, e de forma invisível. O oráculo
também precisa de token: ele roda *depois* da drenagem, quando o original já morreu.

### O experimento de conservação

**Forma: rajada instantânea.** "Em caso de picos" é literalmente uma rajada — a borda está sob
teste, e o que se observa é se algum envio ficou sem desfecho. Fixture de **controle (3 s)**,
`N ≈ 300–500`: aqui interroga-se a borda e a fila, e vídeo pesado só faria a drenagem levar
horas sem acrescentar nada ao que se quer provar (o censo exige desfecho).

**Falhas injetadas, porque pico sem falha prova pouco** — a fila absorve, todo mundo sabe:

1. **`docker kill` numa réplica do `extracao`** durante a drenagem — exercita ack manual,
   requeue e `x-delivery-limit`. Barato, e prova o que a doc chama de "worker morre no meio".
2. **`docker kill videos`** durante a rajada — a única que exercita a **varredura de
   reconciliação** e as colunas marcadoras do [ADR 0003](../../adr/0003-reconciliacao-por-varredura.md).
   É a mais valiosa e a menos óbvia: aquele ADR existe inteiro para fechar a janela entre
   gravar e publicar, e essa janela nunca foi exercitada. Expectativa **realista**: provar que
   a varredura republica (matando durante a rajada e vendo o backlog convergir), não cravar a
   janela de microssegundos.
3. **Restart do RabbitMQ** com fila cheia — só se sobrar tempo. Prova durabilidade de fila
   quorum, que é garantia do broker, não deste desenho: é o RabbitMQ sendo testado.

### Critério, fixado antes de rodar

Sem limiar declarado antes, todo resultado vira narrativa pós-fato.

1. **Zero respostas não-`202`** entre os envios da rajada. Qualquer `5xx`, recusa de conexão ou
   timeout **conta como perda**. Este é o item duro: mede-se o destino dos envios aceitos e
   ignora-se o que a borda recusou — mas conexão recusada em pico *é* a requisição perdida que
   o enunciado proíbe, e é a única forma de perda que a fila não protege.
2. **100% dos IDs da lista em estado terminal** (`CONCLUIDO` ou `FALHOU`) dentro de
   `3 × tempo_esperado_de_drenagem`.
3. **Zero presos** em `RECEBIDO` ou `PROCESSANDO` ao fim.

### Fora deste ticket

Variar réplicas (026). Implementar melhoria (027) — inclusive as que a medição sugerir aqui:
elas vão para o 027 com o número ao lado, não para dentro deste.

## Resolução

O harness está em `scripts/carga/` (`gera-fixtures.sh`, `injetor.js`, `oraculo.sh`,
`conservacao.sh`) mais o overlay [`docker-compose.carga.yml`](../../../docker-compose.carga.yml).
E o veredito é o que o ticket existia para arriscar: **a afirmação não se sustenta**. Sob pico
com falha, o sistema perde requisição — 11 em 400 (2,75%) numa rodada, **34 em 39** depois de
o `videos` cair —, e as perdas são permanentes: nada no desenho as recupera.

**A borda conserva; o que perde é o que vem depois dela.** Em toda rodada com a borda viva,
400 envios simultâneos de 1 MB deram **400 `202`, zero recusas** — sem `5xx`, sem conexão
recusada, sem timeout. O critério 1, que era o item duro, passou sempre. Foram os critérios 2
e 3 que reprovaram, e é uma distinção que muda o diagnóstico: a fila absorve o pico como
prometido, e a perda mora na aplicação do evento terminal, do outro lado.

**O achado central: o evento terminal é descartado em silêncio quando chega fora de ordem.**
`ExtracaoIniciada` e `ExtracaoConcluida` viajam em **filas separadas, com consumidores
independentes** — não existe ordem entre elas. Quando a `Concluida` chega antes da `Iniciada`,
o `UPDATE` condicional de `marcarConcluida` casa o predecessor `PROCESSANDO`, encontra
`RECEBIDO`, altera **zero linhas**, e o consumidor dá ack. `EstadoVideo.transitaPara` chama
isso de "reentrega fora de ordem, que é caminho esperado" — e é, para uma reentrega; para a
**primeira e única** entrega da conclusão, é a perda definitiva do desfecho. Depois a
`Iniciada` chega, o Vídeo vai a `PROCESSANDO` e fica lá para sempre. Nem a varredura do
ADR 0003 o alcança: ela procura marcas de publicação nulas, não Vídeos parados.

A prova não é o raciocínio, é o bucket: dos **45** Vídeos presos ao fim das rodadas, **45 têm
o `.zip` gravado no MinIO**. Cem por cento. A Extração sempre terminou, o Pacote sempre
existiu, e o usuário que faz polling nunca vai saber — para ele, `GET /videos/{id}` responde
`PROCESSANDO` até o fim dos tempos e o Pacote expira em sete dias sem nunca ter sido oferecido.
É exatamente a requisição perdida que o enunciado proíbe, com o agravante de o trabalho ter
sido feito e jogado fora.

**O segundo achado é que a marca do ADR 0003 mente.** Três Vídeos ficaram em `RECEBIDO` com
`comando_publicado_em` **preenchido** — publicado 200 ms depois do `INSERT`, e o comando nunca
chegou ao broker. A causa está na fonte primária: o conector declara
`@ConnectorAttribute(name = "publish-confirms", ..., defaultValue = "false")`
(smallrye-reactive-messaging-rabbitmq 4.32.1), então o `CompletionStage` do envio completa
quando a mensagem entra no canal, **não** quando o broker confirma. O `SIGKILL` levou os
frames em buffer, e a marca já estava gravada. O ADR 0003 raciocina que gravar a marca depois
do publish só arrisca republicar de graça; o que ele não previu é que "publiquei" pode ser
falso. E como a varredura filtra por `comando_publicado_em IS NULL`, esses três nunca mais são
reconsiderados — a marca não é um registro, é um veto.

**O terceiro achado: a varredura de órfãos no boot do `extracao` sabota as réplicas vivas.**
`EspacoDeTrabalhoAdapter.limparOrfaosNoBoot` apaga **todos** os filhos do scratch, e o Javadoc
declara a premissa: "a varredura é segura porque `max-outstanding-messages=1` garante que nada
mais está em voo quando o worker inicia". Isso vale para **uma** réplica. Com N, o volume
nomeado `fiapx-extracao-scratch` é o mesmo para todas — que é o que `--scale extracao=N` faz —,
e a réplica que reinicia apaga o diretório de trabalho das irmãs no meio do `ffmpeg`. Medido:
duas réplicas iniciadas às 09:27:30 registraram `Error submitting a packet to the muxer: No
such file or directory` no instante em que a terceira subiu (09:33:03), e um Vídeo perfeitamente
válido chegou ao usuário como **`ARQUIVO_INVALIDO`**. Um h264 bom declarado ruim, com e-mail e
tudo.

**Duas decisões de instrumento que mudaram o resultado.** A primeira: o denominador vem do
injetor, não do banco — o k6 registra o `id` de cada `202` e o oráculo confere o **`LEFT JOIN`**
a partir dessa lista, com `AUSENTE` para o que foi aceito e não tem linha. Um
`SELECT count(*) FROM video` teria respondido "consistente" em todas as rodadas. A segunda:
o critério **5** não existia. Os quatro originais deixavam passar o `ARQUIVO_INVALIDO`, porque
`FALHOU` é desfecho e os critérios 2 e 3 só perguntam se houve desfecho; foi preciso nomear
"terminal porém errado" como perda e repetir a rodada com o critério declarado antes. Fica no
harness, e vale mais para o 026 do que para este.

**Números.** Rodada limpa (2×): 400 aceitos, 400 terminais em **98 s** com 4 réplicas, latência
do `202` med 3,3 s / p95 5,5 s / max 6,6 s sob 400 conexões simultâneas — a fila absorve, a
borda enfileira e ninguém cai. `mata-extracao` (2×): 399/400 e 389/400. `mata-videos`: dos 39
aceitos antes da queda, **2** chegaram a terminal em 450 s. O limite de drenagem foi apertado
de 3 s para **1 s por Vídeo por réplica** depois da calibração, porque um limite folgado
transforma o critério 2 em "eventualmente termina", que nada reprova.

**Nada foi corrigido aqui, por desenho.** Os três defeitos vão para o
[027](027-melhorias-medidas.md) com o número ao lado. O que este ticket entrega é o instrumento,
o veredito e a causa — e o [026](026-linearidade-horizontal.md) reusa o harness sem construir
nada, como o corte previa.
