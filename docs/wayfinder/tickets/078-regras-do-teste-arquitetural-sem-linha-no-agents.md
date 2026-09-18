# Duas regras novas do teste arquitetural sem linha no `AGENTS.md`

- id: 078
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
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

- [x] A seção cobre as oito regras, nomeando o ticket de origem de cada uma das duas novas
- [x] Cada entrada nova diz por que a regra existe, não só o que ela cobra
- [ ] Nenhuma regra em `ArchitectureConstraintsTest` fica sem menção na seção — não satisfeito
  ao pé da letra; ver Resolução

## Resolução

`AGENTS.md` § *As três cópias do teste arquitetural* ganhou a sétima e a oitava entrada, no
mesmo formato das seis anteriores:

- **Sétima (ticket 064)** — `toleranciaAFalhasNaoPodeSerConfigurada` estende a sexta regra ao
  `application.properties`: nenhuma chave de tolerância a falhas por interceptor pode ser
  configurada, nem no formato do MicroProfile nem nos namespaces `quarkus.fault-tolerance` ou
  `smallrye.faulttolerance`. A sexta regra lê fonte Java, e uma chave de configuração escapa
  dela; foi assim que duas chaves `%test.../Retry/delay` sobreviveram no `videos` configurando
  um `@Retry` que já não existia.
- **Oitava (ticket 068)** — `workersNaoDevemDeclararPacoteDeBordaHttp` proíbe o pacote
  `framework.web` em qualquer serviço diferente de `videos`: `extracao` e `notificacao` não têm
  borda HTTP, e o pacote anunciava um `Resource` que nunca existiria.

O terceiro critério, ao pé da letra, não fica satisfeito: `bordaNaoPodeBuscarVideoSemDono` e
`processoExternoSoDeveApareceEmFramework` continuam sem menção na seção, e "nenhuma regra... fica
sem menção" as inclui. Deixo isso registrado em vez de marcar um `[x]` que a própria Resolução
contradiria — o precedente do ticket 064 (reaberto em parte pelo 076 por um problema análogo:
um critério que não fechava de verdade) é não fingir que fechou.

A leitura que guiou o trabalho é que "a série" do § *As três cópias do teste arquitetural* —
o que o "O problema" deste ticket descreve, numerada de terceira a sexta — é um subconjunto
deliberado: entram aí as regras cujo *porquê* não está no teste. Das duas que ficaram de fora,
só uma se encaixa claramente nessa leitura: `processoExternoSoDeveApareceEmFramework` tem
javadoc no próprio método (linhas 91–95 do teste), citando os tickets 006 e 015 e o motivo —
mesma categoria da quarta regra, que também tira mensageria e scheduler do código de domínio.
`bordaNaoPodeBuscarVideoSemDono`, ao contrário, não tem comentário nem javadoc algum; só a
mensagem de violação ("use buscarPorIdEDono") indica a troca, sem dizer por que ela importa.
Por essa leitura ela deveria ter entrado na série, e não entrou — é o motivo real pelo qual o
terceiro critério fica com a caixa vazia, não uma diferença de interpretação. O "O que entregar"
deste ticket pediu especificamente a sétima e a oitava; documentar `bordaNaoPodeBuscarVideoSemDono`
é trabalho real e não fica implícito em nenhum dos dois, então fica fora desta entrega e virou o
[ticket 084](084-bordanaopodebuscarvideosemdono-sem-porque.md).
