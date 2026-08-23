# Script de smoke ponta-a-ponta

- id: 022
- label: wayfinder:task
- status: aberto
- assignee:
- bloqueado-por: 020

## Question

O mapa já decidiu (Notas, tabela de restrições) que o fluxo ponta-a-ponta é verificado por um
script de smoke versionado, não automatizado no CI. Com o `docker-compose.yml` do ticket 020 de
pé, o roteiro é especificável: um script executável (`scripts/smoke.sh`, mesmo diretório do
`verifica-testes-arquiteturais.sh`) que sobe o Compose, obtém um token do Keycloak, envia o vídeo
de fixture já versionado (`extracao/src/test/resources/fixtures/video-valido.mp4`), espera
`CONCLUIDO`, baixa e valida o Pacote, e cobre o caminho de falha (upload inválido → `FALHOU` → um
e-mail no MailHog). É também o roteiro que vira a demo para a banca — as duas coisas na mesma
peça, não dois artefatos separados.
