# Roteiro do video de ate 10 minutos

- id: 024
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por: 023

## Question

O entregavel final e um video de **no maximo 10 minutos** apresentando documentacao,
arquitetura escolhida e o projeto funcionando. Dez minutos e pouco para tres coisas, entao o
roteiro e sobretudo um **orcamento de tempo** e uma ordem — nao um passeio pelo repositorio.

Duas pecas ja existem e mudam a conta: `scripts/smoke.sh` (ticket 022) executa o "projeto
funcionando" inteiro em ~1 minuto, narrado, sem ninguem digitar comando ao vivo; e o artefato
do ticket 023 e o que sustenta a parte de arquitetura. O que falta decidir: quanto tempo cada
bloco recebe, em que ordem (arquitetura antes ou depois de ver funcionar?), o que **nao**
mostrar — codigo linha a linha, Swagger clicado a mao, consoles de RabbitMQ e MinIO sao os
candidatos obvios a cortar —, se a execucao e ao vivo ou gravada de antemao (o risco de um
`docker compose pull` lento no meio da apresentacao e real), e quais requisitos do enunciado
precisam ser **ditos** porque nao aparecem na tela: nao perder requisicao em pico,
escalabilidade horizontal, qualidade por testes.
