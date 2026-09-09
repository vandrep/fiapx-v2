# O `Rastro` do `notificacao` descreve recursos que o serviço não tem

- id: 088
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `1ebefcb...57da6fc`, convertido em ticket com aprovação
do usuário.

## O problema

O `AGENTS.md` § *As cópias deliberadas entre serviços* manda não forçar as cópias a convergir, e
dá justamente esta razão:

> o `Rastro`, por exemplo, documenta recursos externos diferentes

O `notificacao/.../framework/observabilidade/Rastro.java` não fez essa parte. A seção
*Onde `emTorno` vale a pena, e onde não* chegou do `extracao` quase inteira:

> A mensageria e o Postgres aparecem sozinhos. O MinIO **não**: a extensão da AWS arrasta o
> `opentelemetry-aws-sdk-2.2` e monta o `AwsSdkTelemetry`, mas **nenhum span de S3 chegou ao
> Tempo** num ciclo completo de Vídeo (ticket 059, verificado no `smoke.sh`).

O `notificacao` não tem Postgres — o `AGENTS.md` § *O que difere* registra o banco dele como
nenhum — e não fala com o MinIO. Dois terços do parágrafo descrevem a auto-instrumentação de
outro serviço.

A última frase, essa sim, é do serviço certo: "O que fica sem dono aqui e o SMTP do
quarkus-mailer, ultimo trecho da travessia de um Video que falhou." É ela que justifica o
`emTorno` que a classe realmente usa.

O defeito é de registro, não de comportamento: nenhum span muda. O que muda é o que a próxima
sessão acredita ao abrir o arquivo — e o [061](061-travamento-raro-com-o-sdk-desligado.md) já
descartou uma hipótese inteira por acreditar numa frase desatualizada deste mesmo javadoc, o que
o próprio arquivo narra algumas linhas acima.

## O que entregar

O parágrafo reescrito para os recursos externos que o `notificacao` de fato alcança, preservando
a forma e a intenção da seção — *onde `emTorno` vale a pena aqui, e onde não*. A frase sobre o
SMTP fica.

Vale conferir, de passagem, se o `Rastro` do `videos` tem o mesmo problema ao contrário: ele tem
Postgres e MinIO, então o parágrafo pode estar certo lá por acidente e não por edição.

## Critérios de aceite

- [ ] O javadoc do `Rastro` do `notificacao` não afirma nada sobre Postgres nem sobre MinIO
- [ ] A frase sobre o SMTP como trecho sem dono continua, e continua sendo o que justifica o uso
      de `emTorno` na classe
- [ ] Nenhuma afirmação de medição ("verificado no `smoke.sh`") é atribuída a um serviço em que a
      medição não foi feita
- [ ] O `Rastro` do `videos` foi conferido contra os recursos do `videos`
- [ ] As três cópias continuam divergindo só no que o `AGENTS.md` autoriza divergir; nada de
      comportamento mudou
- [ ] `./mvnw test` verde a partir da raiz
