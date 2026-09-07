# O Compose da demo processa mais de um vídeo ao mesmo tempo

- id: 049
- label: wayfinder:bug
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `3a3ec95...b4672ff`, convertido em ticket com aprovação
do usuário. O primeiro requisito funcional do enunciado é processar mais de um vídeo ao
mesmo tempo. A stack que o README manda subir e que o smoke exercita declara o `extracao`
sem réplicas, e o serviço limita as mensagens em voo a uma. Resultado observável: um vídeo
por vez. A concorrência existe só no overlay de carga, que é harness de medição. A
documentação de arquitetura já dá o requisito como atendido por réplicas independentes — a
arquitetura permite, a entrega não exerce.

## O que entregar

Quem sobe a stack seguindo o README e envia uma rajada de vídeos vê mais de um sendo
extraído ao mesmo tempo, sem precisar do harness de carga nem de flags que o README não
ensina. O requisito passa a ser demonstrável na stack que a banca abre.

## Condições de aceite

- [x] A stack padrão do projeto processa vídeos concorrentemente, por réplicas do worker,
  por mensagens em voo, ou por ambos — a escolha registrada com a razão.
- [x] Observar a concorrência de fora: uma rajada de envios produz extrações sobrepostas no
  tempo, e o resultado de cada vídeo continua correto e atribuído ao seu dono.
- [x] Preservar as garantias que o limite de mensagens em voo protegia hoje, ou registrar
  o que muda e por que é aceitável.
- [x] O README ensina como observar isso, sem depender do overlay de carga.
- [x] O fluxo ponta-a-ponta contra o Compose continua passando.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

**Por réplicas, não por mensagens em voo.** O `docker-compose.yml` da demo passou a subir o
`extracao` com `deploy.replicas: 2`. O `max-outstanding-messages=1` fica exatamente como
estava, e essa é a razão da escolha: o prefetch de 1 protege memória e disco de uma extração
que o ticket 006 mediu em até 4,4 GB de PNG, e subi-lo poria N extrações dentro do **mesmo**
JVM e do mesmo scratch. *Competing consumers* entrega a mesma concorrência sem tocar nessa
garantia — cada réplica continua pegando uma extração por vez, que é o desenho que
`docs/arquitetura.md` § Escalar já descrevia. O limite continua valendo onde ele vale: dentro
de cada JVM.

Duas coisas **mudam** com a réplica extra, e nenhuma delas é o que o prefetch protegia. Ficam
registradas no próprio `docker-compose.yml`, que é onde a decisão mora:

1. O `fiapx-extracao-scratch` é um volume só, montado nas duas réplicas: o pior caso de disco
   da demo dobra, de 4,4 GB para 8,8 GB. É o preço do desenho, e não uma surpresa — o
   ticket 041 deu a cada **tentativa** o seu diretório dentro desse volume justamente porque
   as réplicas o dividem, e a varredura de órfãos com gate por idade (ticket 027) nasceu do
   mesmo compartilhamento.
2. Sem teto de CPU, cada réplica dimensiona o `ffmpeg` por `availableProcessors()` — que sem
   cgroup devolve o host inteiro —, então as duas se sobre-assinam. A eficiência 0,99 do
   ticket 026 foi medida **com** `cpus=2` por réplica e não se transfere para a demo sem teto.
   Aceitável aqui porque a demo demonstra requisito, não mede vazão: o que ela precisa mostrar
   é que dois Vídeos são extraídos ao mesmo tempo. Teto de CPU continua só no overlay, que é
   onde se mede.

Duas, e não quatro: é o menor `N` que demonstra o requisito e o ponto medido com eficiência de
escala 0,99 (ticket 026, 2,96 → 5,87 Vídeo/min). O teto de CPU por réplica continua só no
`docker-compose.carga.yml` — é parâmetro de experimento, e a demo não carrega instrumento. O
`N`, esse virou `${FIAPX_EXTRACAO_REPLICAS:-2}` no arquivo da demo: é a **mesma** variável que
o overlay já usava, então existe uma mecânica só para o conceito, e
`FIAPX_EXTRACAO_REPLICAS=4 docker compose up -d` vale tanto quanto passá-la ao script.

`scripts/concorrencia.sh` é a observação de fora, e o critério foi fixado antes de rodar:
rajada de oito envios autenticados contra a stack padrão, amostrando `GET /videos` a cada
100 ms, e pelo menos **dois** Vídeos em `PROCESSANDO` no mesmo instante. O intervalo em
`PROCESSANDO` é o intervalo da extração — o estado abre em `extracao.iniciada` e fecha em
`extracao.concluida` —, então duas barras sobrepostas na linha do tempo que ele desenha são
duas extrações simultâneas, lidas só pela borda pública. Ele também cobra o resultado: os oito
chegam a `CONCLUIDO`, cada Pacote é um ZIP íntegro com frames, e nenhum deles responde
diferente de `404` para o outro usuário. Concorrência que troca resultado não é concorrência.

São **duas** condições sobre a sobreposição, e a segunda saiu da revisão deste ticket: o
`PROCESSANDO` abre quando o `videos` consome `extracao.iniciada` e fecha quando consome
`extracao.concluida`, em canais diferentes e com prefetch 20 — nada ordena o fim de um Vídeo
contra o começo do outro. Uma réplica única que termina A e já pega B pode, num instante
isolado, aparecer com os dois em `PROCESSANDO`. Por isso o critério exige que a sobreposição
**persista** por pelo menos duas amostras (~200 ms): descarta o artefato de reordenação sem
descartar concorrência de verdade. Medido antes de virar critério — com duas réplicas, as
rodadas sustentaram 3, 4, 4, 4 e 5 amostras das cinco.

O critério foi conferido por controle negativo, não só por passar: com `--scale extracao=1` e
a guarda de réplicas relaxada, o script reprova em `nunca houve mais de 1 Video em
PROCESSANDO ao mesmo tempo` e a linha do tempo sai em escada, sem sobreposição. Com as duas
réplicas, todas as execuções deram pico 2 e Pacotes corretos; com `N=4` e rajada de 16, pico
4; com rajada de 120 — que estoura a página default da listagem, e por isso o script deriva o
`tamanho` da rajada em vez de fixar 100 —, pico 2 sustentado por 18 das 32 amostras.

Nada mais precisa mudar para escalar o worker: ele não publica porta e não guarda estado, ao
contrário da borda, que continua exigindo o proxy do overlay. `--scale` também funciona, mas o
README recomenda a variável: quem passa `--scale` por fora e depois roda o script o vê ser
desfeito pelo `up -d`.

Dois scripts precisavam de ajuste, e nenhum dos dois é cosmético — os dois esperariam até o
timeout com duas réplicas. `scripts/persistencia-rabbitmq.sh` julgava saúde comparando a saída
de `docker compose ps <servico>` com a string `healthy`, e agora exige que exista container e
que nenhum deles esteja fora de `healthy`.

`scripts/smoke.sh` precisou do ajuste irmão: a espera de saúde comparava o
número de **linhas** de `docker compose ps` com 3, e com duas réplicas são quatro linhas para
três serviços — o smoke ficaria esperando até o timeout. Agora conta serviços distintos e
exige nenhum container fora de `healthy`, o que também sobrevive a `--scale`. O fluxo
ponta-a-ponta passa inteiro contra o Compose com as duas réplicas.

O comentário do `scripts/carga/duplicata-em-replicas.sh` também mentia depois desta mudança —
dizia que `docker compose up -d` devolve a stack a uma réplica — e passou a registrar que o
`--scale extracao=2` dele virou redundante, ficando como declaração do que o ensaio exige.

Ficou registrado, e não feito, o que a revisão apontou como duplicação: a espera de saúde, os
helpers de cor e o `passo/ok/falha` são cópia entre `smoke.sh`, `concorrencia.sh` e o ensaio de
persistência. É a norma visível do repo — cada script se lê inteiro no lugar, como as três
cópias do teste arquitetural —, e extrair um `scripts/lib/` mudaria essa norma para todos os
scripts a pretexto deste ticket.

**Suíte verde a partir da raiz**, com infraestrutura real, sem alteração de código Java: 129
testes no `videos`, 268 no `extracao` e 24 no `notificacao`. Como nos tickets anteriores, o
Keycloak da stack ocupa a 8081 nesta máquina, então rodei com `-Dquarkus.http.test-port=0`.

Documentação: o README ganhou a seção *Mais de um vídeo ao mesmo tempo*, com o
`docker compose ps extracao`, o script, um trecho da linha do tempo e como escalar;
`docs/arquitetura.md` deixou de dizer que a demo sobe réplica única do `extracao` — a ressalva
que sobra é só a da borda, que precisa do proxy; e o comentário do
`docker-compose.carga.yml` registra que a separação demo/overlay agora exclui `replicas` do
`extracao`.
