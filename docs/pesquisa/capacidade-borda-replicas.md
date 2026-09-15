# Recusa por capacidade com três réplicas

Medição do [ticket 115](../wayfinder/tickets/115-recusa-por-capacidade-com-varias-replicas-da-borda.md),
em 2026-09-15, sobre `develop @ 72dc146`.

## Método

`scripts/carga/borda.sh escala 3`, 400 envios e 400 VUs, fixture `controle-3s.mp4`
(aproximadamente 1 MB), duas réplicas de `extracao`, cada uma limitada a 1 CPU. O harness
recriou a stack e seus volumes entre rodadas. Overlay `docker-compose.carga.yml`, coletor
ausente e SDK desligado, conforme o método de carga existente. Nenhuma configuração do proxy
foi alterada: um worker, 1024 conexões, `max_fails=1 fail_timeout=2s`, `http_503` presente em
`proxy_next_upstream`, `non_idempotent` ausente e bufferização de corpo ligada.

Imagens locais, as mesmas do ticket 114: `videos` `17ce23bc1408`, `extracao` `5a42fa3a2648`,
proxy `6769dc3a703c` (`nginx:1.27-alpine`, versão efetiva `1.27.5`). O `videos` é o build
local do 108, anterior à mudança de configuração do 113; a medição julga a interação da
recusa existente nessa imagem com o proxy, não certifica a imagem do HEAD atual.

Comandos executados na raiz:

```sh
FIAPX_ROTULO=ticket115-baixo-antes FIAPX_BORDA_TETO_DE_ENVIOS_SIMULTANEOS=1 scripts/carga/borda.sh escala 3
FIAPX_ROTULO=ticket115-derivado scripts/carga/borda.sh escala 3
```

O segundo comando foi executado sem a variável de teto definida. As saídas do harness ficam
em `scripts/carga/saida/<rotulo>/` (ignoradas pelo Git). Os logs completos foram coletados com
`docker logs fiapx-v2-videos-proxy-1` e `docker logs fiapx-v2-videos-<i>`, para i=1,2,3,
antes de recriar a stack. Contagem por réplica: linhas `Envio recusado por capacidade`;
contagem no proxy: status das linhas de access log de `POST /videos`.

## Resultado com teto 1

86 respostas `202`, 314 respostas `503`, nenhum outro status. Recusas por réplica:
`videos-1`: 70; `videos-2`: 75; `videos-3`: 169. A soma é exatamente os 314 `503` do cliente
e do access log do proxy. As três réplicas registraram teto configurado igual a 1.

Rajada em 21,5 s; latência do `202`: mediana 83 ms, p95 1.254 ms, máximo 9.020 ms.
86/86 chegaram a `CONCLUIDO`, drenagem de 32 s, zero presos, zero `FALHOU`, zero frames
errados; amostra de dez pela API passou. O harness saiu com código 1 exclusivamente pelo
critério de zero não-`202`: reprovação esperada nesta calibração que força recusa.

Não houve `upstream server temporarily disabled` durante a rajada (início às 09:05:22 UTC).
As únicas três linhas desse tipo são de 09:05:12 UTC, no boot: o healthcheck
`GET /q/health/ready` encontrou `connect() failed (111: Connection refused)` nas três
réplicas. Não houve `no live upstreams` nem `worker_connections are not enough`.
Os avisos de corpo bufferizado em arquivo temporário são do funcionamento normal do proxy.

## Controle com teto derivado

As três réplicas registraram teto derivado de 2354. Foram 400/400 respostas `202`, tanto no
injetor quanto no access log, e zero recusas por capacidade em cada uma das três réplicas.
Rajada em 41,2 s; latência do `202`: mediana 229 ms, p95 10.093 ms, máximo 12.000 ms.
400/400 `CONCLUIDO` em 114 s de drenagem: os seis critérios do harness passaram, incluindo
amostra de dez pela API e três frames por Pacote. Código de saída 0.

Novamente, as únicas três desabilitações do proxy ocorreram no healthcheck durante o boot,
às 09:07:24 UTC, antes das réplicas registrarem o teto às 09:07:27 UTC. Nenhuma
desabilitação na rajada, nenhum `no live upstreams`, nenhum limite de conexões atingido.
As latências das duas rodadas são descritivas: uma aceita 86 envios e a outra 400, e não
houve repetição estatística nem controle de aquecimento para comparar desempenho.

## Interpretação e decisão

**Nesta configuração, o `503` de capacidade do `POST /videos` não tirou a réplica de
circulação.** Não se observou a cascata de indisponibilidade suspeitada no ticket.
A distribuição desigual de recusas, sozinha, não demonstra que uma réplica foi excluída.

O resultado é coerente com
[`ngx_http_upstream_test_next` no nginx 1.27.5](https://github.com/nginx/nginx/blob/release-1.27.5/src/http/ngx_http_upstream.c#L2400):
para um `POST` já enviado, a máscara exigida inclui `NON_IDEMPOTENT`. Sem essa opção, a
condição para chamar `ngx_http_upstream_next` por status HTTP não é satisfeita. Portanto,
nesse caminho, nem o reenvio nem a contabilização de falha são acionados. Isso não se
generaliza a falha de conexão, timeout ou método idempotente.

**Manter `http_503` no proxy de carga.** Sua remoção não corrige um defeito observado nesse
fluxo. Não houve alteração do proxy; portanto não há comparação antes/depois de
`mata-replica 3` neste ticket nem nova afirmação sobre o custo de matar réplica medido no 028.
Se a política de reenvio ou o método mudar, esta conclusão precisa ser reavaliada.

O experimento força o teto de concorrência com arquivos pequenos, não esgota o disco.
Não prova uma reserva global de espaço: cada réplica continua descontando só suas reservas
do volume compartilhado. Essa limitação foi registrada em
[`docs/arquitetura.md`](../arquitetura.md#limitações-conhecidas), junto das limitações do 109.
