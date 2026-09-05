# O ciclo de vida da Extração mora no `extracao`

- id: 032
- label: wayfinder:task
- status: fechado
- assignee:
- bloqueado-por: 029, 030

## Question

Existem **duas** máquinas de estado neste sistema, e só uma está modelada. O `CONTEXT.md` já
sabe disso: *"Uma Extração pode ser tentada mais de uma vez para o mesmo Vídeo. 'Falhar uma
tentativa' não é o mesmo que 'falhar definitivamente'"*. A Extração tem ciclo próprio —
tentativa, início, falha transitória, falha permanente, conclusão. O Vídeo tem outro. Em
código só a segunda existe; a primeira vive espalhada entre o `x-delivery-limit=3` do RabbitMQ
e a classificação por exit code dentro de `FfmpegExtracaoDeFramesAdapter` — **280 linhas, zero
testes**.

O estado do Vídeo é a **projeção do desfecho da Extração** sobre o Vídeo, e não a Extração em
si. `RECEBIDO` é a exceção: esse é do Vídeo, e existe antes de qualquer Extração.

A regra mais consequente do sistema — permanente contra transitória, que decide se o Dono
recebe e-mail ou se o Vídeo gasta uma tentativa — está atrás da costura do processo externo,
alcançável só produzindo um arquivo real que faça o ffmpeg sair com 183, 8 ou 234.

## O que muda

A costura fica onde está; a **decisão** atravessa. O adapter entrega exit code, stderr,
contagem de frames do `ffprobe` e duração a um valor no `core`, que responde com um
`MotivoFalha` ou "transitória". Idem para a tolerância de 10% na contagem de frames e para o
teto de duração de 20 minutos.

A tabela de `docs/pesquisa/ffmpeg-extracao.md` e a de `docs/contratos/mensagens.md` § motivos
deixam de ser prosa e viram teste tabelado. O ffmpeg continua necessário para a mecânica —
`ProcessBuilder`, timeouts, ZIP `STORED`, `-threads` — e deixa de ser necessário para a regra.

## Por que depende do 029 e do 030

Os dois mexem em quando uma tentativa é gasta e onde ela termina. Reescrever a classificação
antes deles significaria escrever os testes contra uma política de falhas que muda na semana
seguinte.

## Não é "extrair para testar"

`Motivo da falha` é vocabulário do `CONTEXT.md`, e os call sites ficam onde estão: a
localidade melhora em vez de espalhar. O que sai do adapter é decisão de domínio que nunca
deveria ter morado atrás de um processo externo.

## Condição de aceite

A tabela exit code → `MotivoFalha` coberta por teste sem ffmpeg no classpath, e o
`FfmpegExtracaoDeFramesAdapter` menor do que entrou.
