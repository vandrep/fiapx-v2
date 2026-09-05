# Contexto: FIAP X — processamento de vídeos

Glossário canônico do domínio. Termos em português, usados igualmente em código,
mensagens, endpoints e conversa. Este arquivo é **só glossário** — decisões de
implementação ficam no mapa (`docs/wayfinder/map.md`) e nos ADRs.

## Vídeo

O arquivo que o usuário envia para processamento, junto com tudo que o sistema sabe sobre
ele: quem é o dono, quando chegou, em que estado está e qual Pacote produziu.

Todo Vídeo tem exatamente um dono, identificado pelo `sub` do token de acesso. Um usuário
só enxerga os próprios Vídeos.

O **Dono** de um Vídeo é o par identidade + endereço de e-mail, ambos vindos do token no
momento do envio. Os dois andam sempre juntos: a identidade é por quem se pergunta, o
e-mail é para onde o aviso de falha vai. O sistema não trata o formato da identidade como
significativo — ela é uma string opaca vinda de quem autentica.

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

## Estacionamento

Uma fila terminal: destino de uma mensagem que esgotou o próprio fundo — inclusive o fundo
de uma DLQ — e cujo desfecho só um humano produz, olhando o management UI. Não é a mesma
coisa que um Vídeo preso: aquele é estado do domínio (`PROCESSANDO` para sempre), este é
estado de mensageria. As duas coisas às vezes coincidem — uma mensagem que estaciona pode
deixar um Vídeo preso —, mas nem toda fila terminal tem um Vídeo do outro lado, e "terminal"
sem qualificação hoje já nomeia dois conceitos diferentes: o estado final de um Vídeo
(`CONCLUIDO`, `FALHOU`) e o fim de linha de uma fila. Este glossário reserva Estacionamento
para o segundo.

## Pacote

O arquivo `.zip` contendo os frames produzidos por uma Extração bem-sucedida. É o que o
usuário baixa. Um Vídeo `CONCLUIDO` produziu exatamente um Pacote.

O Pacote tem **prazo**: ele fica disponível por um tempo e depois deixa de existir. Isso não
desfaz nada do que se sabe sobre o Vídeo — ele continua `CONCLUIDO`, porque o estado conta o
que aconteceu com a Extração, não o que ainda está guardado. "Não está pronto" e "não existe
mais" são situações diferentes, e o sistema as distingue para quem pede o download.

## Serviços

| Serviço | Responsabilidade |
|---|---|
| `videos` | Borda pública. Recebe o Vídeo, é dono do seu estado, lista os Vídeos do usuário e entrega o Pacote |
| `extracao` | Worker sem estado. Executa a Extração e publica o que aconteceu |
| `notificacao` | Avisa o usuário quando a Extração do seu Vídeo falha definitivamente |

## Mensagens

Os serviços conversam por cinco mensagens, e os nomes delas são vocabulário de domínio —
usados igualmente em conversa, código e documentação. Comandos são nomeados no imperativo,
porque são uma ordem com destinatário; eventos no particípio, porque são um fato consumado.

| Mensagem | O que significa |
|---|---|
| `ExtrairVideo` | `videos` pede ao `extracao` que execute a Extração de um Vídeo |
| `ExtracaoIniciada` | o `extracao` de fato começou a trabalhar num Vídeo |
| `ExtracaoConcluida` | a Extração terminou e produziu um Pacote |
| `ExtracaoFalhou` | a Extração de um Vídeo falhou definitivamente |
| `VideoFalhou` | um Vídeo caiu para `FALHOU` — é o que faz o usuário ser avisado |

`ExtracaoFalhou` e `VideoFalhou` descrevem o mesmo acidente vistos de lugares diferentes: o
primeiro é o worker relatando o que aconteceu com o trabalho, o segundo é o dono do estado
anunciando que o Vídeo mudou. Só o segundo gera e-mail, e ele só existe quando a transição
de estado de fato ocorreu — é aí que mora a garantia de que a notificação não se multiplica.

O `extracao` e o `notificacao` nunca conversam entre si: tudo passa pelo `videos`.

O Vídeo em si nunca trafega numa mensagem. O que circula é a referência ao arquivo
armazenado, nunca o seu conteúdo.

**Motivo da falha** é um código estável e pequeno, não uma frase. A frase que o usuário lê
é escolhida pelo `notificacao`, que é quem conhece o contexto de e-mail; o detalhe técnico
que acompanha o código serve a diagnóstico e nunca chega ao usuário.

O código do motivo não é vocabulário interno de mensageria: ele também é **público**, porque
a consulta a um Vídeo `FALHOU` o devolve. O que não se duplica é a *frase* — a API mostra o
código, e só o `notificacao` o traduz.

Os contratos completos vivem em [`docs/contratos/mensagens.md`](docs/contratos/mensagens.md)
(entre serviços) e [`docs/contratos/http-videos.md`](docs/contratos/http-videos.md) (borda
pública).
