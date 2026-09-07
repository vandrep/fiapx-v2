# O último nome qualificado inline, em `PostgresRetry`

- id: 065
- label: ready-for-agent
- status: fechado
- assignee: andrepinedacunha@gmail.com
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...39712e7`, convertido em ticket com
aprovação do usuário.

## O problema

O [ticket 054](054-nomes-qualificados-inline.md) trocou nomes totalmente qualificados inline
por imports em todo o código. `PostgresRetry.desembrulhar` ficou de fora: ele testa
`java.util.concurrent.CompletionException` e `java.util.concurrent.ExecutionException`
escritos por extenso, tendo `TimeoutException` do mesmo pacote importado poucas linhas acima
— a inconsistência está dentro do mesmo arquivo. É o único caso restante em todo o código de
produção dos três serviços.

## O que entregar

As duas classes importadas como o resto do arquivo já faz.

Uma pergunta a responder de passagem, e a resposta vale escrita mesmo se for "não": dá para
cobrar isso por teste sem falso positivo? O caso legítimo existe — colisão de nome simples
entre pacotes obriga a qualificar um dos lados —, então uma regra ingênua reprovaria código
correto. Se não der, o ticket registra por que a regra do 054 continua sendo convenção lida em
revisão, e não guarda de build; foi por isso que este resquício sobreviveu a uma varredura
inteira.

## Critérios de aceite

- [x] Nenhum nome qualificado inline em `PostgresRetry`
- [x] O ticket responde por escrito se a regra vira guarda de build ou continua convenção, com o motivo
- [x] `./mvnw test` verde a partir da raiz

## Resolução

`CompletionException` e `ExecutionException` agora são importadas, como os demais tipos do
arquivo. A regra continua sendo convenção de revisão, sem guarda de build: um teste baseado
apenas na presença de nomes qualificados inline produziria falso positivo quando dois tipos
homônimos de pacotes diferentes precisassem coexistir no mesmo arquivo. Distinguir esse caso
legítimo exigiria resolução semântica completa dos tipos e ainda transformaria uma preferência
de legibilidade, sem efeito de comportamento ou arquitetura, em complexidade permanente no
teste arquitetural.

`./mvnw test` a partir da raiz, com Docker e `ffmpeg`/`ffprobe` disponíveis:
139 (`videos`) + 276 (`extracao`) + 28 (`notificacao`) testes, sem falha.
