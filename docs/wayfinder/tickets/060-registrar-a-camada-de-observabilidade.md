# Registrar a camada de observabilidade: docs, ADR e roteiro

- id: 060
- label: ready-for-agent
- status: aberto
- assignee:
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

- [ ] `docs/arquitetura.md` nos três pontos: a nota sobre a stack recomendada, a linha da
  tabela de recusados (que passa a recusar **painel curado e canal de notificação**, não
  monitoramento inteiro) e *Limitações conhecidas*.
- [ ] Quatro limitações novas, escritas sem eufemismo: **(1)** os alertas existem e guardam
  histórico, mas **a detecção não mudou** — sem canal de notificação, um alerta só é visto
  por quem já foi olhar, que é exatamente a propriedade do health check no incidente de
  06/09; **(2)** retenção efêmera, o histórico morre no `down`; **(3)** a imagem da stack é
  declaradamente de demonstração, não de produção; **(4)** a configuração medida difere da
  entregue, porque o overlay de carga desliga a observabilidade.
- [ ] `docs/contratos/mensagens.md` ganha a linha dizendo que os headers passam a carregar
  contexto de trace W3C, ao lado do que já diz sobre `x-death`. Deixar explícito que os
  **corpos** das cinco mensagens não mudaram.
- [ ] `AGENTS.md` ganha a regra de nomes: auto-instrumentação como o OTel emite, métrica
  própria no vocabulário do `CONTEXT.md`.
- [ ] **ADR 0004**, cobrindo as três decisões que um leitor futuro questionaria e que hoje não
  têm onde ser respondidas: `core` sem span (e o teste arquitetural endurecido); `idVideo`
  como chave que o humano digita × `trace_id` como identificador da travessia; e o overlay de
  carga desligando a observabilidade para preservar o método das medições já registradas.
- [ ] `CONTEXT.md` **não muda** — decisão, não esquecimento. Os alertas se apoiam em termos
  que o glossário já define (Estacionamento, tentativas esgotadas, Vídeo preso), e trace,
  span e travessia são vocabulário de infraestrutura. O glossário é glossário.
- [ ] Bloco 4 do `docs/roteiro-video.md`: as ~60 palavras que narram a recusa do monitoramento
  viram ~15 de afirmação — sobra orçamento, não falta, e o roteiro é medido palavra a palavra
  contra um teto duro de 10:00 (fecha hoje em 9:41). **Remeça a contagem**; a tabela de blocos
  no topo do arquivo precisa continuar verdadeira.
- [ ] Tomada de demonstração no **Bloco 2**, reaproveitando uma espera que já é acelerada em
  `4×` — o passo de trace do `smoke.sh` (059) mostrando o caminho do Vídeo pelos três
  serviços. Não abrir tomada nova: um painel vazio é péssimo vídeo, e a prova aqui é o
  caminho, não o gráfico.
- [ ] Uma linha em *Decisões até aqui*, no mapa, para cada ticket fechado da cadeia.

## Dependências

Bloqueado pelo 058 e pelo 059: documenta o que os dois entregam, e a contagem de palavras do
roteiro depende da tomada que o 059 torna possível.
