# Contexto: FIAP X — processamento de vídeos

Glossário canônico do domínio. Termos em português, usados igualmente em código,
mensagens, endpoints e conversa. Este arquivo é **só glossário** — decisões de
implementação ficam no mapa (`docs/wayfinder/map.md`) e nos ADRs.

## Vídeo

O arquivo que o usuário envia para processamento, junto com tudo que o sistema sabe sobre
ele: quem é o dono, quando chegou, em que estado está e qual Pacote produziu.

Todo Vídeo tem exatamente um dono, identificado pelo `sub` do token de acesso. Um usuário
só enxerga os próprios Vídeos.

Estados de um Vídeo:

| Estado | Significado |
|---|---|
| `RECEBIDO` | Armazenado e enfileirado; nada foi extraído ainda |
| `PROCESSANDO` | A Extração está em andamento |
| `CONCLUIDO` | A Extração terminou e há um Pacote disponível para download |
| `FALHOU` | A Extração falhou definitivamente, após esgotadas as tentativas |

Não existe estado separado para "aguardando na fila": do ponto de vista do usuário, isso é
`RECEBIDO`.

As transições de estado do Vídeo são **idempotentes**: aplicar a mesma transição duas vezes
tem o mesmo efeito que aplicá-la uma vez. Só a transição que de fato mudou o estado tem
consequências — é ela que faz o sistema anunciar o que aconteceu. Repetir uma transição já
efetuada não reanuncia nada, e é por isso que a notificação de falha não se multiplica.

## Extração

A operação que lê um Vídeo e produz seus frames — hoje, uma imagem por segundo. É o
trabalho que o serviço `extracao` executa; não é algo que o usuário pede diretamente, mas
a consequência de ter enviado um Vídeo.

Uma Extração pode ser tentada mais de uma vez para o mesmo Vídeo. "Falhar uma tentativa"
não é o mesmo que "falhar definitivamente" — só a falha definitiva leva o Vídeo a `FALHOU`
e gera notificação.

Uma **tentativa** é uma *entrega* do trabalho ao serviço `extracao`, não um erro. Se o
worker morre no meio de uma Extração, aquela tentativa foi gasta, ainda que nada tenha dado
errado com o Vídeo. Isso é deliberado: um Vídeo que derruba o worker repetidamente esgota
suas tentativas e falha definitivamente.

## Pacote

O arquivo `.zip` contendo os frames produzidos por uma Extração bem-sucedida. É o que o
usuário baixa. Um Vídeo `CONCLUIDO` tem exatamente um Pacote.

## Serviços

| Serviço | Responsabilidade |
|---|---|
| `videos` | Borda pública. Recebe o Vídeo, é dono do seu estado, lista os Vídeos do usuário e entrega o Pacote |
| `extracao` | Worker sem estado. Executa a Extração e publica o que aconteceu |
| `notificacao` | Avisa o usuário quando a Extração do seu Vídeo falha definitivamente |
