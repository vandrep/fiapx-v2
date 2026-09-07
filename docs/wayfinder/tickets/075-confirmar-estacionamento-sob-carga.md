# A garantia de estacionamento do 029 nunca foi confirmada sob carga

- id: 075
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem

Achado do eixo Spec da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 029](029-terminal-na-dlq-do-extracao.md) está `fechado`, e a própria `## Resolução`
dele diz que a garantia **não** foi provada:

> A segunda parte, o modo `mata-publicacao` do `conservacao.sh`, só conseguiu subir depois do
> 038, e chegou ao veredito com o critério do estacionamento **reprovado** por prazo: zero
> mensagens novas em 241 s, limite de 240 s. A garantia deste ticket, portanto, está construída
> e provada em teste, mas **não confirmada sob carga**.

Ela aponta para o [038](038-override-de-canal-por-variavel-quebra-o-boot.md) como quem carrega
o diagnóstico pendente. O 038 também está `fechado`, e a sua resolução devolve a pendência sem
dono:

> O boot está corrigido; a garantia de estacionamento do 029 **não foi confirmada** no prazo do
> harness e permanece pendente de diagnóstico. A medição não distingue atraso, circulação ou
> perda; não se atribui causa sem investigar.

Dois tickets fechados apontando um para o outro fecham o assunto sem que ninguém o carregue. O
que está em jogo não é registro: é o fundo de toda a recuperação do sistema — o caminho que
transforma o `x-delivery-limit=3` esgotado em `ExtracaoFalhou` e impede o Vídeo de ficar em
`PROCESSANDO` para sempre ([ADR 0001](../../adr/0001-politica-de-falhas.md)).

O [073](073-agents-md-descreve-conservacao-de-um-estado-que-passou.md) remediu `limpo` e
`mata-videos` contra o código atual, e os dois passaram. `mata-publicacao` ficou de fora, então
a única medição que existe dele é a de antes dos tickets 061 a 069.

## O que entregar

- Uma rodada de `scripts/carga/conservacao.sh mata-publicacao` contra o **HEAD atual**, com as
  imagens reconstruídas antes de medir. O 073 registrou o gotcha que invalida a rodada: o
  harness mede a imagem que estiver por perto, e imagens de um dia atrás passam despercebidas.
- O veredito com os três critérios julgados, e o número do critério do estacionamento.
- Se aprovar: a `## Resolução` do 029 ganha a confirmação que faltava, e este ticket registra a
  medição.
- Se reprovar: a causa nomeada — atraso, circulação ou perda —, e o 029 **reabre**, porque a
  garantia que ele declara não existe. Não se atribui causa sem investigar; o diagnóstico é
  parte do trabalho, não a rodada sozinha.

## Como medir

Corrida longa: use `systemd-inhibit --what=sleep:idle`, como o 073 fez. O host suspende no meio
de uma medição de mais de dez minutos e corrompe o denominador em silêncio.

## Critérios de aceite

- [ ] Rodada de `mata-publicacao` contra imagens construídas do HEAD, com o log guardado
- [ ] Os três critérios julgados, com o número do estacionamento registrado neste ticket
- [ ] O 029 confirmado ou reaberto, conforme o veredito — não fechado com aceite reprovado
- [ ] Se reprovou, a causa nomeada entre atraso, circulação e perda
