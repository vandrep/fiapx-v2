# O passo 12 do smoke julga o painel antes da segunda exportação

- id: 110
- label: ready-for-agent
- status: aberto
- assignee: claude (sessão de 2026-09-13, SHA inicial 769978f)
- bloqueado-por:
- prioridade: P2

## Origem

Achado ao validar o [103](103-dead-lettering-at-least-once-sem-reject-publish.md) em
2026-09-13, sobre `develop @ 769978f`. O critério de aceite do 103 exige `scripts/smoke.sh` e
`scripts/persistencia-rabbitmq.sh` verdes, e os dois reprovam em stack recém-criada pelo mesmo
motivo. O mantenedor escolheu abrir este ticket e fechar o 103 depois dele.

## O defeito

Numa stack recém-criada, o passo 12 do `scripts/smoke.sh` reprova o painel *Borda — recusas do
contrato (4xx), por status*:

```
FALHOU  painel 'Borda — recusas do contrato (4xx), por status' (A) não devolveu nada num sistema
que acabou de processar um Vídeo: sum by (http_response_status_code)
(rate(http_server_request_duration_seconds_count{job="fiapx-videos", http_response_status_code=~"4.."}[5m]))
```

Com a stack já em uso, o mesmo passo passa. Foram quatro corridas medidas:

| Stack | Policy do broker | Resultado do passo 12 |
|---|---|---|
| recém-criada (`smoke.sh`) | nova, do 103 | reprova no painel 4xx |
| em uso (`smoke.sh` de novo) | nova | passa, 12 amostras |
| recém-criada (smoke no fim do `persistencia-rabbitmq.sh`) | nova | reprova no painel 4xx |
| recém-criada (`smoke.sh`, com o `definitions.json` de `689b7d9`) | **antiga** | reprova no painel 4xx |

A última linha prova que o defeito é anterior ao 103 e não depende do broker.

## O que foi medido

- **O `videos` exporta métrica por OTLP a cada 60 s.** É o default do SDK, e nada no repositório
  o configura. Amostras cruas de `http_server_request_duration_seconds_count` chegam com 60 s
  exatos de intervalo (`…894`, `…954`, `…014`, `…074`, `…134`).
- **O smoke inteiro cabe na primeira janela de exportação.** Na corrida com a policy antiga, o
  smoke terminou em `…889` e a primeira amostra de **qualquer** série HTTP do `videos`, inclusive
  a do `/q/health/ready` que o healthcheck chama desde o boot, chegou em `…894`. No passo 12 o
  Prometheus não tinha nenhuma amostra HTTP do `videos`. `rate()` exige duas.
- **O passo 11 não é a causa.** Um `docker compose stop observabilidade` seguido de `start` manual
  preservou as amostras anteriores no Prometheus da `grafana/otel-lgtm:0.32.1`. A explicação que o
  103 tinha registrado, de que o restart zerava a série, estava errada.
- **Só os painéis da *Borda* dependem de duas amostras.** Os de fila e de Extração consultam valor
  instantâneo e passam com uma. O de 5xx também usa `rate()`, mas não chegou a ser julgado, porque
  o laço para no primeiro que reprova.

## Observação lateral: o 404 do passo 9 não foi contado

Na corrida com a policy antiga, a série `404` de `/videos/{id}` **não existia** depois do smoke;
existiam só a `401` (passo 3) e a `409` (passo 7). Dois `GET /videos/<uuid inexistente>` manuais
fizeram a série nascer com valor **2**, e não 3. O 404 do passo 9 não entrou na métrica. Nas
corridas do 103 com a stack em uso ela existia. É o mesmo padrão da série `401` que o comentário
do passo 12, escrito no 097, registra como sumida "sem explicação". Não é o defeito deste ticket,
mas desmente a frase do mesmo comentário "basta esse 404 para a query ter série". A correção não
pode depender do 404.

## Escopo

1. No passo 12, antes do laço das queries, esperar que o Prometheus tenha **duas amostras de
   alguma série 4xx do `fiapx-videos`** na janela de 5 min, com teto de duas exportações e
   margem (150 s). A condição olha a métrica e o rótulo crus que o painel usa, e não a query do
   painel, para que a espera não aprove o que o laço existe para julgar.
2. No teto, reprovar com mensagem própria, dizendo as duas causas possíveis: exportação parada,
   ou métrica ou rótulo renomeado.
3. Corrigir o comentário do passo 12 que atribui a série ao 404 do passo 9.
4. Não mudar o intervalo de exportação do SDK. Baixá-lo só para o smoke mudaria o Compose que o
   smoke existe para julgar.
5. Não investigar aqui o 404 não contado. Fica registrado acima.

## Critérios de aceite

- [ ] `scripts/smoke.sh` verde contra uma stack recém-criada (`docker compose down` sem `-v`,
      depois o script).
- [ ] `scripts/smoke.sh` verde contra a stack em uso, logo em seguida.
- [ ] `scripts/persistencia-rabbitmq.sh` verde.
- [ ] Linha em "Decisões até aqui" no mapa.
