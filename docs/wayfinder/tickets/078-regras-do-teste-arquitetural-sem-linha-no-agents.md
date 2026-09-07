# Duas regras novas do teste arquitetural sem linha no `AGENTS.md`

- id: 078
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P2

## Origem

Achado do eixo Standards da revisão de `08d76ed...c592711`, convertido em ticket com aprovação
do usuário.

## O problema

O `AGENTS.md` § *As três cópias do teste arquitetural* narra a história das regras uma a uma:
duas asserções do template chegaram relaxadas, "uma terceira mudou no ticket 016", "uma quarta
chegou no ticket 017", "uma quinta chegou no ticket 034", "uma sexta chegou no ticket 061". Cada
entrada diz o que a regra cobra e por que ela existe — é o único lugar onde o *porquê* de cada
uma está escrito, já que o teste é a autoridade sobre o *quê*.

A revisão acrescentou duas regras e não continuou a série:

- `toleranciaAFalhasNaoPodeSerConfigurada` ([064](064-chaves-orfas-de-fault-tolerance.md)) —
  estende a sexta regra ao `application.properties`, pelo mesmo motivo que o 034 estendeu a
  cobertura a config: chave órfã sobrevive ao interceptor que saiu, e fonte Java não a enxerga.
- `workersNaoDevemDeclararPacoteDeBordaHttp` ([068](068-framework-web-em-worker-sem-borda-http.md))
  — `extracao` e `notificacao` não têm borda HTTP, então `framework.web` neles nomeia algo que
  não existe.

O `AGENTS.md` declara que "as regras de camada não estão escritas aqui — estão no teste", e que
este arquivo "só carrega o que ele não consegue dizer". O porquê é justamente o que o teste não
diz. Duas regras sem linha deixam o registro mentindo por omissão: quem lê a série conclui que
são seis.

## O que entregar

A série continuada com a sétima (064) e a oitava (068), no mesmo formato e no mesmo nível de
detalhe das anteriores — o que a regra cobra, por que ela existe, e o ticket que a trouxe.

## Critérios de aceite

- [ ] A seção cobre as oito regras, nomeando o ticket de origem de cada uma das duas novas
- [ ] Cada entrada nova diz por que a regra existe, não só o que ela cobra
- [ ] Nenhuma regra em `ArchitectureConstraintsTest` fica sem menção na seção
