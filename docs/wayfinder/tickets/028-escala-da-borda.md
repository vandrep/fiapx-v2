# Escala da borda

- id: 028
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 027 (fechado)

## Question

A afirmação sob julgamento é a linha do `videos` na tabela *§ O que escala, e como* de
`docs/arquitetura.md`: **réplicas**, porque o estado está no Postgres e a transição é `UPDATE`
condicional. Aquela célula carrega hoje um **"Nunca medido"** e o número que o
[025](025-carga-conservacao.md) produziu sem querer: derrubar a réplica única durante uma
rajada custou **361 envios recusados de 400** — 239 timeouts de conexão, 53 EOF, 46 resets,
22 recusas.

Nasceu de um corte: o [026](026-linearidade-horizontal.md) julga a linearidade do `extracao` e
esta pergunta tentou entrar lá. É outro experimento — outro objeto (HTTP, não fila), outro
critério (recusa e latência, não vazão de drenagem) e construção nova de verdade. O valor do
corte 025/026 foi não deixar duas perguntas dividirem um veredito, e vale de novo aqui.

### Por que nasce bloqueado

O [027](027-melhorias-medidas.md) precisa fechar antes, e o motivo não é organização: o modo
`mata-videos` do harness, que é o instrumento óbvio para esta pergunta, hoje **mede os defeitos
1 e 2 do 025** em vez de medir a borda. Foi ele que produziu 34 Vídeos presos de 39 aceitos —
com o `.zip` no bucket e o usuário sem saber. Enquanto o evento terminal for descartado fora de
ordem e a marca do [ADR 0003](../../adr/0003-reconciliacao-por-varredura.md) puder mentir,
qualquer coisa que este ticket medisse estaria contaminada por eles, e a conclusão seria sobre
os defeitos, não sobre a réplica.

### O que precisa ser construído

Ao contrário do 026, aqui não dá para reusar e varrer:

- **proxy no overlay de carga** (`docker-compose.carga.yml`), com `videos` em N réplicas atrás
  dele. O `docker-compose.yml` principal continua sendo a demo da banca e não recebe nada
  disto — mesma regra do 025;
- o injetor apontado para o proxy, e não para `videos:8080` direto;
- o critério: hoje o k6 mede latência do `202` e conta recusas, que é quase tudo o que esta
  pergunta pede. Falta o que acontece **durante** a queda de uma réplica de N.

### As duas perguntas, que são diferentes

1. **A borda escala?** N réplicas atrás do proxy aguentam mais envios simultâneos que uma —
   e a partir de onde o gargalo deixa de ser a borda e passa a ser Postgres ou MinIO.
2. **A borda sobrevive à queda de uma réplica?** Esta é a que interessa ao enunciado: com N>1,
   matar uma durante a rajada deveria custar **zero** requisição, porque as outras continuam
   aceitando. É a diferença entre os 361 recusados do 025 e o que o desenho promete.

A segunda vale mais que a primeira. A primeira é vazão de borda, que ninguém duvida; a segunda
é a única forma de perda que a fila não protege, e é o que o `arquitetura.md` afirma sem ter
visto.

### Critério, a fixar antes de rodar

Não aqui — mas com a mesma regra que os dois tickets anteriores seguiram: limiar declarado
**antes**, senão todo resultado vira narrativa pós-fato. E os portões de validade do 026 valem
(`quantidade_frames`, zero `FALHOU` com fixture válido, zero presos): a borda "aceitar" não é
o desfecho, é o começo dele.

### Saída

Números em `docs/pesquisa/` e a célula do `videos` na tabela § *O que escala, e como* perdendo
o **"Nunca medido"** — para ganhar um número, seja ele qual for. Se o resultado reprovar, ele
vai para § *Limitações conhecidas* com o número ao lado, que é o padrão que o 025 estabeleceu.


## Desbloqueado pelo 027

Os defeitos 1 e 2 que envenenavam o modo `mata-videos` estão corrigidos e remedidos (0/133
terminais com a borda derrubada), então a pergunta desta ficha — *matar uma réplica de N deveria
custar zero requisição* — já é medível. Três coisas que o 027 entrega prontas:

- **`FIAPX_ATRASO_KILL`** no harness: os 3 s fixos não alcançavam a janela, e sob 400 VUs
  simultâneos nada completa antes do kill. As rodadas úteis daqui saíram com `FIAPX_VUS=40`.
- **Portão de validade de rodada** no modo `mata-videos`: rodada com 0 aceitos agora sai com
  código 2 em vez de passar verde. Vale igual aqui, onde o alvo do kill também é a borda.
- **Aviso de instrumento**: nada no repo constrói as imagens que o Compose referencia
  (`ghcr.io/vandrep/fiapx-*:latest`). Construa antes de medir, ou meça o binário antigo em
  silêncio.

O número de linha de base continua o do 025: **361 recusados de 400** ao derrubar a réplica
única.
