# Piso de observabilidade: stack, métricas de broker e alertas

- id: 058
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por:
- prioridade: P1

## Origem

Redesenho de destino de 06/09/2026. Monitoramento estava em *Fora de escopo* porque
canibalizaria o tempo do CI/CD; entregue o CI/CD e esvaziada a fronteira, a recusa perdeu a
premissa e sobrou orçamento. O que motiva o piso é concreto e recente: em 06/09 as duas
réplicas do `extracao` subiram com imagem defasada e morreram em laço de
`PRECONDITION_FAILED` ao redeclarar `extracao.extrair.dlq`. Por 14 minutos a fila teve
mensagem e **zero consumidores**, e todo Vídeo enviado ficou em `RECEBIDO`. O dado existia —
`docker ps` dizia `unhealthy` — mas nada o transformava em pergunta respondível, e o
diagnóstico levou 20 minutos de leitura de log.

`docs/arquitetura.md` § Limitações conhecidas já nomeava exatamente esta lacuna: *"a primeira
coisa que eu acrescentaria com mais tempo seria visibilidade sobre profundidade de fila e
taxa de dead-letter"*.

Este ticket entrega o piso: a stack de coleta de pé e as perguntas de fila respondidas, sem
tocar em código Java. O 059 instrumenta os serviços; o 060 documenta.

## O que entregar

A stack de observabilidade sobe junto com a demo, coleta as métricas do broker e avalia três
alertas sobre condições que hoje só um humano descobre abrindo o management UI. Um `up`
normal passa a responder "quantas mensagens há na fila", "alguma coisa morreu na DLQ" e
"existe consumidor vivo" sem que ninguém leia log. O fluxo de Vídeo continua íntegro com a
stack ausente, morta ou nunca iniciada.

## Condições de aceite

- [ ] `grafana/otel-lgtm` em **um** container no `docker-compose.yml` principal — não em
  overlay. Observabilidade passou a ser parte do sistema, não instrumento de medição; o
  critério é o mesmo que graduou `extracao` para `replicas: 2` no ticket 049.
- [ ] Nenhum `depends_on` dos três serviços de negócio para a stack, e nenhum caminho em que
  a ausência ou a morte dela impeça boot, processamento ou resposta HTTP. Derrubar o
  container e verificar que um Vídeo completa o ciclo mesmo assim.
- [ ] Retenção efêmera: sem volume nomeado. O histórico morre no `down`, e isso é escolha —
  volume sem teto na máquina de quem avalia é pior que perder histórico.
- [ ] Métricas de fila do RabbitMQ coletadas — o plugin `rabbitmq_prometheus` já vem na
  imagem `management` fixada, então isto é configuração de scrape, não componente novo.
- [ ] Três alertas avaliados, todos binários e sem limiar para calibrar: **(1)**
  `extracao.extrair.estacionamento` não-vazio; **(2)** `extracao.extrair.dlq` com mensagem;
  **(3)** fila com mensagem e **zero consumidores**. O (3) é o incidente de 06/09 e teria
  disparado em um minuto.
- [ ] Os alertas vivem só no Grafana; **nenhum** canal de notificação nesta entrega. A
  detecção não muda, e a limitação vai escrita no 060 — sem essa linha o documento sugere
  garantia que o sistema não dá.
- [ ] Medir e registrar, com número: RAM e tempo de `docker compose up` **com e sem** a
  stack. Este projeto decide por medição; a escolha do container único não pode ficar por
  argumento.
- [ ] O overlay `docker-compose.carga.yml` **desliga** a observabilidade, preservando o
  método das medições dos tickets 025–028 — elas foram feitas sem a stack, e medir
  linearidade limitada por CPU com um coletor disputando o mesmo host mede outra coisa.
  Registrar a sobrecarga uma vez, sobre o fixture de controle, em vez de varrer N de novo.
- [ ] Linha nova na tabela de URLs do `README.md`, ao lado de Swagger, Keycloak, RabbitMQ,
  MinIO e MailHog, com a credencial que a imagem já traz — sem escrever configuração de
  autenticação para uma stack efêmera de demonstração.

## Dependências

Nenhuma. Não toca em código Java e pode começar imediatamente. Bloqueia o 059 (que exporta
para esta stack) e o 060 (que documenta o conjunto).

## Resolução

**Implementado.** `grafana/otel-lgtm:0.32.1` (Collector + Prometheus + Tempo + Loki + Grafana)
entrou como serviço `observabilidade` no `docker-compose.yml` principal, sem `depends_on` de
nenhum serviço de negócio, sem volume nomeado e publicando só a porta 3000. Versão fixada,
como todas as outras imagens do projeto.

**Os três números prometidos**, todos medidos nesta máquina:

| | Memória do host | Tempo até saudável |
|---|---|---|
| Host ocioso, zero containers | 5.814 MiB | — |
| Demo completa, sem observabilidade | 7.857 MiB | ~42 s |
| Demo completa, com observabilidade | 8.222 MiB | +23 s |

**A observabilidade custa 365 MiB de RAM** — bem menos do que cinco containers separados
custariam, e menos do que eu estimava antes de medir. O custo real desta escolha não é
memória, é **disco: a imagem tem 3,6 GB**, e é o que quem avalia baixa uma vez. A stack
interna sobe em 12 s; os 23 s incluem o `pull` já feito e o healthcheck.

**Sobrecarga sobre o fixture de controle: não distinguível do ruído.** Três corridas de
`smoke.sh` com a stack de pé deram 7 s, 6 s e 7 s; três sem ela deram 6 s, 7 s e 6 s. Os dois
conjuntos ocupam a mesma faixa. **Ressalva que importa**: isto mede só a competição do
container por recursos, porque neste ticket os três serviços ainda não exportam nada — o custo
da instrumentação em si não existe até o 059, e é lá que ele precisa ser medido de novo.

**Métricas.** O plugin `rabbitmq_prometheus` já vinha habilitado na imagem `management` fixada,
então não houve componente novo. O que houve foi uma descoberta: o `/metrics` padrão devolve
métrica **agregada, sem rótulo de fila**, e as três perguntas do ticket são todas por fila. O
scrape vai em `/metrics/detailed`, pedindo só as famílias `queue_coarse_metrics` e
`queue_consumer_count`. Os nomes sobrevivem intactos à ida e volta por OTLP —
`rabbitmq_detailed_queue_messages`, `_messages_ready` e `_consumers` chegam ao Prometheus como
saíram do broker, o que foi verificado antes de escrever as regras.

**Os três alertas** estão provisionados e avaliando (`health=ok`, `state=inactive` com o
sistema são). O terceiro foi validado **reproduzindo o incidente de 06/09**: com o `extracao`
parado e uma mensagem sonda publicada em `extracao.extrair`, a expressão disparou com
`extracao.extrair | prontas: 1 | consumidores: 0`. A sonda foi purgada antes de religar o
`extracao`, para não gastar três entregas com um payload que não é `ExtrairVideo`.

O único número calibrado é o `for: 5m` do alerta 3, e ele é derivado, não chutado: um redeploy
do `extracao` zera o consumidor por instantes e o dreno do ticket 035 tem teto de 420 s; cinco
minutos tolera o deploy e ainda pega o incidente de 06/09, que durou 14 minutos. Os outros dois
usam `for: 1m` só contra jitter de scrape. Todos com `noDataState: OK` — série ausente é
sistema são, não alerta.

O alerta 3 pega, de quebra, as DLQs terminais (`videos.dlq`, `notificacao.dlq`), que nascem com
zero consumidores por design. Isso é desejado e não é ruído: fila terminal **vazia** não
dispara, porque a condição exige mensagem **e** ausência de consumidor — e mensagem nelas
também só sai por intervenção humana.

**Três achados sobre a imagem**, que estão em comentário no lugar onde importam: ela não traz
`wget` nem `curl` (o healthcheck usa `test -f /tmp/ready`, a marca de prontidão que ela mesma
publica e que cobre as cinco peças); o Grafana lê provisionamento relativo ao seu cwd,
`/otel-lgtm/grafana`, e não `/data/grafana`, que só guarda dados e plugins; e ela sobe com
**acesso anônimo e papel Admin** por padrão, o que resolve o acesso sem escrever configuração
de autenticação e sem gastar segundos de vídeo com login — daí a linha do README não ter
credencial.

`docker-compose.carga.yml` desliga a stack com `replicas: 0`, preservando o método das medições
dos tickets 025–028. `replicas: 0` e não `profiles` porque a garantia precisa valer para a
corrida, não para a intenção: com replicas zero, uma stack já de pé é reduzida a zero pelo `up`
do overlay; com profile, ela continuaria rodando.

**Validações**: `scripts/smoke.sh` completo passou com a stack de pé — os nove passos, incluindo
o ciclo `RECEBIDO → PROCESSANDO → CONCLUIDO`, o ZIP íntegro, o `ARQUIVO_INVALIDO` e o e-mail no
MailHog. As três regras carregam e avaliam. Não houve mudança em código Java, nem em contrato,
nem no `application.properties` de nenhum serviço.

O que este ticket **não** entrega, e está registrado: nenhum canal de notificação — os alertas
vivem só no Grafana, então **a detecção não mudou**; e retenção efêmera, o histórico morre no
`down`. As duas limitações vão para `docs/arquitetura.md` no ticket 060.
