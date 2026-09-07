# Registrar a camada de observabilidade: docs, ADR e roteiro

- id: 060
- label: ready-for-agent
- status: fechado
- assignee: vandrep
- bloqueado-por: 058, 059
- prioridade: P2

## Origem

A ausência de monitoramento não está registrada num lugar só: ela é afirmada em
`docs/arquitetura.md` em três pontos (a nota sobre a stack recomendada, a tabela *O que foi
recusado*, e *Limitações conhecidas* com a frase *"Não há observabilidade além de health
check. Sem métrica, sem tracing distribuído"*) e é **narrada no vídeo de entrega**, no Bloco
4 do `docs/roteiro-video.md`. Entregues o 058 e o 059, esses quatro textos passam a mentir.

O ticket 055 existiu exatamente para eliminar escolhas sem registro onde a banca olha. Este é
o mesmo trabalho, na direção inversa.

## O que entregar

Os documentos voltam a descrever o sistema que existe, incluindo o que a camada
deliberadamente **não** faz. Um leitor que pergunte "por que não há span no `core`?" ou "por
que a medição de escalabilidade roda numa configuração diferente da entregue?" encontra a
resposta escrita.

## Condições de aceite

- [x] `docs/arquitetura.md` nos três pontos: a nota sobre a stack recomendada, a linha da
  tabela de recusados (que passa a recusar **painel curado e canal de notificação**, não
  monitoramento inteiro) e *Limitações conhecidas*.
- [x] Quatro limitações novas, escritas sem eufemismo: **(1)** os alertas existem e guardam
  histórico, mas **a detecção não mudou** — sem canal de notificação, um alerta só é visto
  por quem já foi olhar, que é exatamente a propriedade do health check no incidente de
  06/09; **(2)** retenção efêmera, o histórico morre no `down`; **(3)** a imagem da stack é
  declaradamente de demonstração, não de produção; **(4)** a configuração medida difere da
  entregue, porque o overlay de carga desliga a observabilidade.
- [x] `docs/contratos/mensagens.md` ganha a linha dizendo que os headers passam a carregar
  contexto de trace W3C, ao lado do que já diz sobre `x-death`. Deixar explícito que os
  **corpos** das cinco mensagens não mudaram.
- [x] `AGENTS.md` ganha a regra de nomes: auto-instrumentação como o OTel emite, métrica
  própria no vocabulário do `CONTEXT.md`.
- [x] **ADR 0004**, cobrindo as três decisões que um leitor futuro questionaria e que hoje não
  têm onde ser respondidas: `core` sem span (e o teste arquitetural endurecido); `idVideo`
  como chave que o humano digita × `trace_id` como identificador da travessia; e o overlay de
  carga desligando a observabilidade para preservar o método das medições já registradas.
- [x] `CONTEXT.md` **não muda** — decisão, não esquecimento. Os alertas se apoiam em termos
  que o glossário já define (Estacionamento, tentativas esgotadas, Vídeo preso), e trace,
  span e travessia são vocabulário de infraestrutura. O glossário é glossário.
- [x] Bloco 4 do `docs/roteiro-video.md`: as ~60 palavras que narram a recusa do monitoramento
  viram ~15 de afirmação — sobra orçamento, não falta, e o roteiro é medido palavra a palavra
  contra um teto duro de 10:00 (fecha hoje em 9:41). **Remeça a contagem**; a tabela de blocos
  no topo do arquivo precisa continuar verdadeira.
- [x] Tomada de demonstração no **Bloco 2**, reaproveitando uma espera que já é acelerada em
  `4×` — o passo de trace do `smoke.sh` (059) mostrando o caminho do Vídeo pelos três
  serviços. Não abrir tomada nova: um painel vazio é péssimo vídeo, e a prova aqui é o
  caminho, não o gráfico.
- [x] Uma linha em *Decisões até aqui*, no mapa, para cada ticket fechado da cadeia.

## Dependências

Bloqueado pelo 058 e pelo 059: documenta o que os dois entregam, e a contagem de palavras do
roteiro depende da tomada que o 059 torna possível.

## Resolução

**Implementado.** Nenhuma linha de código Java, de contrato ou de Compose mudou: este ticket é
inteiramente registro. O que mudou foram seis arquivos, e a razão de cada um é a mesma —
entregues o 058 e o 059, quatro textos passaram a mentir.

**`docs/arquitetura.md`, nos três pontos.** A nota sob *Requisitos do enunciado* passou de
*"monitoramento ficou de fora conscientemente"* para o que existe, com o que a busca por
`idVideo` devolve e os três alertas. A linha da tabela de recusados deixou de recusar
monitoramento inteiro e passou a recusar **painel curado e canal de notificação de alerta**,
que é o que de fato ficou fora. E *Limitações conhecidas* perdeu a linha *"Não há
observabilidade além de health check"* e ganhou quatro no lugar.

**As quatro limitações**, sem eufemismo, porque cada uma é uma garantia que o documento
poderia sugerir e o sistema não dá:

1. **Os alertas existem e a detecção não mudou.** As três regras avaliam e guardam histórico e
   nenhuma sai do Grafana. Um alerta só é visto por quem já foi olhar — que é exatamente a
   propriedade do health check no incidente de 06/09/2026, em que `docker ps` dizia `unhealthy`
   por 14 minutos. O que a camada acrescentou ali foi o **diagnóstico**, não a **descoberta**.
2. **Retenção efêmera**: sem volume nomeado, o histórico morre no `down`. Consequência escrita:
   nenhuma pergunta sobre ontem tem resposta, e é por isso que os três alertas são binários — um
   limiar calibrado por tendência precisaria de uma série que não existe.
3. **A imagem é declaradamente de demonstração**: cinco peças num container, acesso anônimo em
   papel Admin, sem persistência. Comprou o piso por 365 MiB e 3,6 GB; não compra operação.
4. **A configuração medida não é a entregue.** O overlay de carga desliga a stack e o SDK, então
   os números de escala deste projeto descrevem um sistema sem observabilidade e a demo tem uma.
   O custo foi medido à parte (~5%, ~160 MiB), e **extrapolá-lo para o regime de pico é conta que
   ninguém fez**. Some-se o [061](061-travamento-raro-com-o-sdk-desligado.md), que só apareceu no
   caminho com o SDK desligado.

**ADR 0004** cobre as três decisões que um leitor futuro questionaria: `core` sem span (com a
parte que mais importa — o teste arquitetural era **nominal e fechado**, então antes do 059 um
`@WithSpan` num use case passaria em silêncio e a regra mentiria por omissão); `idVideo` ×
`trace_id`, numa tabela de cinco linhas, com as duas consequências que foram medidas e não
previstas (a busca só pelo atributo casa dezenas de traces de uma linha; o span do `ffmpeg` não
carrega `idVideo`); e o overlay que desliga a observabilidade, com a alternativa recusada dita —
varrer N de novo custaria as horas de todas aquelas corridas para responder pergunta que ninguém
fez. Entrou também na tabela de ponteiros do `AGENTS.md`.

**`docs/contratos/mensagens.md` ganhou `### Headers`**, ao lado do parágrafo do `x-death` e pelo
mesmo argumento: metadado de transporte anda no header, não no corpo. Diz explicitamente que os
**corpos das cinco mensagens não mudaram**, que a regra *alterou aqui, alterou nos três* vale para
os `record` e não para header, e que um consumidor que ignore `traceparent` funciona igual.

**`AGENTS.md` ganhou § Nomes na observabilidade**: o que a auto-instrumentação emite fica como o
OTel emite (contrato com a ferramenta), o que é nosso usa o vocabulário do `CONTEXT.md`
(`fiapx.extracao.duracao`, `resultado=concluida|falhou`, `idVideo`), instrumentação só em
`framework`, e métrica nova precisa da mesma justificativa que a primeira teve.

**`CONTEXT.md` não mudou**, como o ticket pedia, e o ADR 0004 registra que é decisão e não
esquecimento — os alertas se apoiam em Estacionamento, tentativas esgotadas e Vídeo preso, que o
glossário já define, e trace, span e travessia são vocabulário de infraestrutura.

**Roteiro.** As **53 palavras** que narravam a recusa do monitoramento viraram **16** de
afirmação. O Bloco 2 ganhou o passo de trace **dentro da tomada que já existia** — a do passo 9
passou a cobrir 9 e 10, sem abrir tomada nova e sem filmar painel —, e a nota do bloco diz por que
o passo 11 também fica fora. A contagem foi refeita palavra a palavra com o mesmo método do
arquivo: **1.404 palavras, 9:41**, exatamente o teto de antes, com os quatro blocos e a tabela do
topo recontados. Sobrou orçamento e ele foi devolvido, não gasto.

**Um número novo, medido**, porque o roteiro citava *"um minuto e oito segundos"* de antes do 058
e do 059: três corridas de `scripts/smoke.sh` contra a stack de pé e quente deram **45, 45 e 46
segundos** para os doze passos (0 a 11), todas verdes. A narração passou a citar as duas metades
que **estão** medidas — a subida do Compose, que o 058 cronometrou em ~42 s mais 23 s da stack, e
os 45 s da verificação — em vez do total composto de antes.

**Uma linha em *Decisões até aqui*** para o 060; o 058 e o 059 já tinham a sua. *Fora de escopo* já
havia sido reescrito para **painel curado e canal de notificação** quando o destino foi
redesenhado, e continua coerente com a linha nova da tabela de recusados.

## O que a revisão mudou

Quatro correções, todas do revisor:

- **Um fato errado no roteiro.** A narração nova dizia "os onze passos"; `scripts/smoke.sh`
  numera de 0 a 11, que são **doze**. Além de errado, o número contradizia a própria nota do
  bloco, que diz que a tomada mostra só os passos 2 a 10. A frase deixou de contar passos.
- **A informação de partida a frio, que eu havia perdido.** Ao trocar o "um minuto e oito
  segundos" obsoleto pelos 45 s medidos, sumiu o que aquela frase existia para dizer: quanto
  custa subir do zero. Voltou, agora com as duas metades citadas separadamente, cada uma com
  medição atrás.
- **`AGENTS.md` repetia o que o teste arquitetural é autoridade sobre.** O arquivo declara, em
  *Onde a verdade mora*, que "só carrega o que ele não consegue dizer", e o parágrafo novo
  enumerava nominalmente os dois imports e as cinco anotações proibidas — uma terceira cópia de
  uma lista que já tem dono executável. Virou ponteiro.
- **A célula nova da tabela *O que foi recusado* trazia a discussão inteira inline**, contra o
  preâmbulo da própria seção ("cada linha tem a discussão inteira no arquivo apontado") e contra
  o "aponta, não repete" que o `AGENTS.md` atribui a esse arquivo. As outras cinco linhas são uma
  frase; esta voltou a ser uma. O ponteiro circular no fim da quarta limitação saiu junto.

A contagem do roteiro foi refeita depois das duas primeiras: **1.404 palavras, 9:41**.

## O que este ticket não entrega

- **Nenhum diagrama novo em `docs/arquitetura.md`.** A stack não virou caixa no diagrama de
  Containers: as condições de aceite nomeiam três pontos de texto, e mexer nos cinco diagramas
  obrigaria a re-renderizar todos para o Bloco 3 do vídeo por uma caixa que não tem seta de
  negócio nenhuma ligada a ela.
- **Dois números estagnados no roteiro, que não são desta cadeia e ficam registrados em vez de
  corrigidos às cegas:** o Bloco 3 e o Bloco 4 dizem *"cento e trinta testes"* (o 059 relata 430,
  e `docs/arquitetura.md` diz 144) e o Bloco 4 diz *"vinte e quatro tickets"* (o rodapé de
  `docs/arquitetura.md` diz 28, e o mapa já passou de 60). O Bloco 4 também mantém *"a
  escalabilidade é argumentada, não medida"*, que os tickets 025–028 desmentiram. Os três são
  anteriores a esta cadeia e mudam a contagem de palavras do roteiro; corrigi-los é ticket
  próprio, não emenda de rodapé.
