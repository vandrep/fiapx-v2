# Harness de carga e prova de conservação sob pico

- id: 025
- label: wayfinder:task
- status: aberto
- assignee:
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
