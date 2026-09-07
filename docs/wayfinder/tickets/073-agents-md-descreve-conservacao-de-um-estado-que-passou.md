# O AGENTS.md descreve o `conservacao.sh` de um estado que passou

- id: 073
- label: ready-for-agent
- status: fechado
- assignee: agente de implementacao (sessao de 2026-09-07)
- bloqueado-por:
- prioridade: P2

## Origem

Contradição entre os dois eixos da revisão de `3a3ec95...39712e7`: o eixo Standards leu o
AGENTS.md, o eixo Spec leu o ticket 027 e o código, e os dois não batem. Convertido em ticket
com aprovação do usuário.

## O problema

O AGENTS.md, § Rodar, diz do `scripts/carga/conservacao.sh`:

> Hoje ele **reprova de propósito**: três defeitos medidos e ainda abertos, em
> `docs/wayfinder/tickets/027-melhorias-medidas.md`.

O 027 está `status: fechado`, e os quatro defeitos que ele registra aparecem corrigidos no
código — predecessor como `Set` no `EstadoVideo` e nas consultas do `VideoDataSourceAdapter`,
`publish-confirms` nos dois canais de saída, gate por idade no `EspacoDeTrabalhoAdapter`,
`-threads` por `availableProcessors()` no adapter de `ffmpeg`.

Ou seja: a instrução que um agente lê antes de rodar o script anuncia uma reprovação esperada
que talvez não exista mais. O custo disso é específico e ruim — quem rodar o script e vir
falha vai atribuí-la aos "três defeitos conhecidos" e seguir em frente, que é precisamente como
uma regressão nova passa despercebida. Um texto desatualizado sobre um script de medição não é
ruído de documentação; ele desarma o script.

## O que entregar

**Rode o `conservacao.sh`** — é a única forma de responder, e o ticket não fecha por leitura de
código. Ele manda uma rajada de centenas de envios contra o Compose com falha injetada
(`docker kill`), julgada por critérios fixados antes de rodar; leve mais de dez minutos, então
use `systemd-inhibit` para o host não suspender no meio.

Depois:

- **Se passar**: o § Rodar do AGENTS.md perde a frase da reprovação esperada e passa a descrever
  o que o script é hoje — a rede que reprova onde o `smoke.sh` passa. O mapa ganha a linha.
- **Se reprovar**: o ticket registra por qual critério, e isso é achado novo, não os defeitos do
  027. Ou se abre um ticket para ele, ou o 027 é reaberto com a medição em anexo — e o AGENTS.md
  passa a apontar para o registro certo.

Nos dois casos, sai do repositório a situação atual: um texto que não corresponde a nenhuma
medição recente.

## Critérios de aceite

- [x] O `conservacao.sh` foi executado nesta rodada, e o resultado está no ticket
- [x] O § Rodar do AGENTS.md descreve o comportamento medido, com o ticket que o registra
- [x] Se houver reprovação, ela tem ticket próprio ou reabre o 027 com a medição

## Resolução

**Passou.** As imagens `ghcr.io/vandrep/fiapx-{videos,extracao,notificacao}:latest` locais eram
de `2026-09-06T19:38`, anteriores aos três últimos commits de código (067, 068, 069) — o
gotcha que o próprio 027 registrou ("o harness mede a imagem que estiver por perto"). Reconstruídas
com `./mvnw package` + `docker build` a partir do HEAD atual (`406392d`) antes de medir, senão a
rodada mediria o binário de um dia atrás em silêncio.

Duas rodadas sob `systemd-inhibit --what=sleep:idle`:

| Modo | Critério | Resultado |
|---|---|---|
| `limpo` (400 envios) | zero recusas, 100% terminal, zero preso, zero `FALHOU` | 400/400 `CONCLUIDO` em 98s, 0 recusas, 0 presos |
| `mata-videos` (400 envios, `videos` morto aos 3s) | ao menos 1 aceito antes da queda (rodada válida), 100% terminal, zero preso | 41 aceitos antes da queda, 41/41 `CONCLUIDO` em 11s, 0 presos |

Os dois modos passaram em todos os critérios — `mata-videos` é o que exercita os defeitos 1
(terminal fora de ordem) e 2 (marca do ADR 0003) que o 027 corrigiu, e não achou nenhum vídeo
preso. O terceiro defeito do 027 (gate por idade na varredura de scratch) não é exercitado por
este script; ele mora no boot, não na rajada.

O § Rodar do AGENTS.md perdeu a frase de reprovação esperada e passou a apontar para este
ticket como a medição que sustenta "ele passa".
