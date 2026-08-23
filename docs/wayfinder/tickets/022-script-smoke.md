# Script de smoke ponta-a-ponta

- id: 022
- label: wayfinder:task
- status: fechado
- assignee: vandrep (sessao de 2026-08-23)
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

## Resolução

`scripts/smoke.sh`, 9 passos, ao lado do `verifica-testes-arquiteturais.sh`. Executado de
ponta a ponta contra o Compose de verdade três vezes: com a stack já de pé, com a stack de pé
e um Keycloak inexistente (para ver o script reprovar), e **do zero absoluto** (`docker
compose down -v` antes) — este último em **1m08s**, do `up` ao último `OK`.

**As duas coisas na mesma peça, e isso mudou o formato.** Um script de smoke mudo (`set -e` e
silêncio até o `echo OK` final) verificaria igual, mas ninguém o projetaria numa
apresentação. Como o mapa decidiu que este fluxo *não* vai para o CI, ele só prova algo se
alguém o rodar — e o que faz alguém rodar é ele narrar. Então cada passo anuncia o que vai
fazer e imprime a resposta do serviço (o JSON do Vídeo, o `problem+json` do `409`, a listagem
do ZIP), e o encadeamento das verificações vira o roteiro: `202` → `RECEBIDO` →
`PROCESSANDO` → `CONCLUIDO` → ZIP com 3 frames é literalmente a ordem em que a banca precisa
ver o assíncrono acontecer.

Três achados que só apareceram executando:

**`docker compose up --wait` não serve aqui.** O `minio-seed` é one-shot e sai com 0; o
`--wait` trata container que saiu como serviço morto e devolve erro. A espera ficou num
`docker compose ps --format '{{.Service}} {{.Health}}'` sobre os **três serviços de negócio
apenas** — a saúde deles já implica a de todo o resto, porque o `depends_on:
service_healthy` do ticket 020 é o que os deixou subir.

**Contar e-mails no MailHog daria falso verde.** A caixa sobrevive entre execuções, então
"existe uma mensagem" passaria na segunda rodada com o e-mail da primeira. A asserção procura
o e-mail **deste** Vídeo, pelo `idVideo` que o `NotificacaoDeFalha` põe no corpo como
referência de suporte. E aí aparece a segunda camada: o corpo tem acentos (decisão do ticket
014), logo vem em quoted-printable, e a quebra leve de 76 caracteres (`=\r\n`) pode partir o
UUID ao meio — o `contains` só é confiável depois de um `gsub` que remove as quebras. A busca
nativa do MailHog (`/api/v2/search`) foi testada e funcionou, mas depende da mesma sorte de
enquadramento; a varredura com `gsub` não depende.

**`set -euo pipefail` engolia o diagnóstico.** Com o Keycloak inalcançável, o script morria no
`exit 7` do `curl` dentro da substituição de comando, *antes* da linha que explica o que era
esperado. Toda captura de saída de `curl` ganhou `|| true`, para que quem reprova seja sempre
a checagem seguinte, que sabe dizer qual requisição era e qual status se esperava.

Duas escolhas menores, pelo mesmo raciocínio de reprodutibilidade e consistência: o caminho de
falha usa o **fixture versionado** (`arquivo-invalido.txt` copiado para `.mp4`), não o
`head -c 2000 /dev/urandom` que o README sugere, porque a demo precisa dar `ARQUIVO_INVALIDO`
toda vez; e a saída do script é **sem acentos**, como a do `verifica-testes-arquiteturais.sh`
— a exceção de prosa acentuada do ticket 014 foi aberta para o e-mail ao usuário final, não
para ferramenta de desenvolvedor.

Dois passos foram além do enunciado do ticket e ficaram: `GET /videos` **sem token** tem de
dar `401` (sem isso, todo o resto do script poderia estar medindo uma API aberta sem
ninguém notar) e o Vídeo do `demo` tem de dar **`404` para o `outro`** — é o requisito de
proteção por usuário, e não há como demonstrá-lo sem dois tokens.

**O smoke não encontrou nenhum defeito**, e isso era esperado: o ticket 020 já havia
percorrido este fluxo à mão. O que ele acrescenta não é a descoberta, é a repetição — a
verificação passou de "alguém fez uma vez" para um comando.

`README.md` ganhou "O caminho inteiro em um comando" antes da seção manual; `AGENTS.md`
ganhou o ponteiro que faltava, e que é o motivo de o script existir: **`./mvnw verify` não
prova que os três serviços conversam**, porque cada suíte testa um serviço isolado com Dev
Services próprios.
