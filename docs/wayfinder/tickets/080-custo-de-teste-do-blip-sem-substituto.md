# O custo de teste que o 048 comprou voltou sem substituto

- id: 080
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P3

## Origem

Achado dos dois eixos da revisão de `08d76ed...c592711`, convertido em ticket com aprovação do
usuário.

## O problema

O [ticket 064](064-chaves-orfas-de-fault-tolerance.md) tirou do `application.properties` do
`videos` as duas chaves que zeravam o `delay` do `@Retry` no perfil de teste. Elas eram órfãs de
um interceptor que já tinha saído, e tirá-las estava certo. O que saiu junto foi o efeito que o
[048](048-retry-no-acesso-ao-minio-pela-borda.md) tinha comprado, e o comentário removido dizia qual:

> com os 2s de produção, um envio contra armazenamento persistentemente fora custaria 6s parados
> por cenário.

Hoje a repetição vive em `comRepeticao`, no `ArquivoMinioClient`:
`ESPERA_ENTRE_REPETICOES` é `Duration.ofSeconds(2)`, constante `private static final`, e
`withBackOff` a usa como mínimo e máximo. Não há perfil que a altere. O
`EnvioResisteABlipDoArmazenamentoTest` não mudou no intervalo, então o cenário voltou a pagar a
espera real.

O 064 previu exatamente este caso e pediu registro:

> se algum cenário depender do `delay` zerado, a dependência era do interceptor que já saiu, e o
> ticket registra o que a passou a substituir.

A `## Resolução` dele diz apenas "Contagem e espera seguem os valores do codigo". Isso descreve
o estado, não registra a troca: o que o 048 tinha comprado deixou de existir e ninguém decidiu
pagar de novo.

## O que entregar

Uma das duas, decidida e escrita:

- A espera volta a ser ajustável por perfil de teste — configuração no `ArquivoMinioClient`, não
  chave de interceptor —, e o cenário do blip volta a custar o que custava. O que está sob teste
  continua sendo a repetição acontecer, não quanto ela espera; foi essa a leitura do 048.
- Ou o custo fica, medido e registrado neste ticket e no Javadoc do teste, como escolha de manter
  a espera de produção sob teste.

Em qualquer dos dois casos, o Javadoc do `EnvioResisteABlipDoArmazenamentoTest` descreve a
proteção que existe hoje e o que o cenário custa.

## Critérios de aceite

- [ ] O custo atual do cenário do blip está medido e escrito
- [ ] A escolha entre ajustar e pagar está registrada, com o motivo
- [ ] Se a espera virar configurável, nenhuma chave de tolerância a falhas por interceptor volta
      ao `.properties` — a guarda do 064 continua verde
- [ ] `./mvnw test` verde a partir da raiz
