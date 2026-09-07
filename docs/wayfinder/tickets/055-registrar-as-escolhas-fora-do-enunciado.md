# Registrar as escolhas que o enunciado não pediu

- id: 055
- label: wayfinder:task
- status: fechado
- assignee: andrepinedacunha@gmail.com
- bloqueado-por:
- prioridade: P3

## Origem

Achados do eixo Spec da revisão de `3a3ec95...b4672ff`, convertidos em ticket com aprovação
do usuário. Quatro coisas no repositório não têm resposta escrita em lugar nenhum. O
monitoramento aparece na stack recomendada do enunciado e não foi feito — a decisão existe
no mapa, mas não no material que a banca assiste. As skills de agente versionadas, o
devcontainer e o CSS de branding do Swagger entraram sem ticket e sem relação com o
enunciado; o CSS em particular esconde um campo do formulário de autorização, o que é
escolha de demo, não decoração. Nenhum deles é defeito: o que falta é o registro.

## O que entregar

Quem abre o repositório ou assiste ao vídeo encontra, para cada uma dessas quatro escolhas,
uma frase que diz que ela foi deliberada e por quê. Nenhum código muda.

## Condições de aceite

- [x] A ausência de monitoramento aparece no roteiro do vídeo como decisão, não como
  omissão, coerente com o que o mapa já registra em fora de escopo.
- [x] As skills de agente versionadas e o devcontainer ganham uma linha que explica por que
  vivem no repositório da entrega.
- [x] O branding do Swagger ganha uma linha dizendo o que ele esconde do formulário de
  autorização e por que a demo precisa disso.
- [x] Nenhum arquivo de código, configuração de serviço ou contrato é alterado.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

Quatro linhas, sem mudança de código. `docs/roteiro-video.md`, Bloco 4 (Limitações
conhecidas): nova frase tratando a ausência de Prometheus/Grafana como recusa deliberada
— stack recomendada, não requisito, e 5,5 semanas solo não sobram para dashboard —,
coerente com `docs/wayfinder/map.md` § Fora de escopo. A narração cresceu 53 palavras
(1.352 → 1.405); o vídeo fecha em 9:41 em vez de 9:19, ainda dentro do teto de 10:00, e a
tabela de blocos e a frase de abertura do roteiro foram recalculadas para bater com o
texto.

`README.md` ganhou a seção `### Ferramental de agente versionado`, explicando por que
`.claude/skills/` e `.devcontainer/` estão no repositório de entrega em vez de num
`.gitignore`: as skills automatizam o fluxo de tickets do wayfinder, e o devcontainer fixa
o toolchain que qualquer clone precisa para rodar `./mvnw verify`.

`README.md` § Usar ganhou uma frase ao lado de onde já manda clicar em Authorize,
explicando que o CSS de branding esconde `client_id`, `client_secret` e o seletor de
client credentials location porque este client público só tem uma resposta certa para os
três. O comentário dentro do próprio
`videos/src/main/resources/META-INF/branding/smallrye-open-api-ui.css` já registrava o
mesmo "porquê" para quem lê código; faltava a frase para quem só abre o repositório.

Nenhum arquivo de código, configuração de serviço ou contrato mudou — só
`docs/roteiro-video.md`, `README.md` e `docs/wayfinder/map.md`.
