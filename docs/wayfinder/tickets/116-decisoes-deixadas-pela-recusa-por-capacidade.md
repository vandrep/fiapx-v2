# Decisões que a recusa por capacidade deixou para o mantenedor

- id: 116
- label: ready-for-human
- status: fechado
- assignee: Codex (sessão de 2026-09-15, SHA inicial 8b0e0ed)
- bloqueado-por:
- prioridade: P3

## Origem

Implementação e revisão do [ticket 108](108-recusa-por-capacidade-antes-do-corpo.md), em
2026-09-14, sobre `develop @ 0ec5e9b`. O 108 tomou duas decisões que o ticket não pedia, as
registrou como limites e as deixou para o mantenedor. Um ticket só, por decisão do mantenedor
em 2026-09-14. Formato de sessão de grilling.

## As duas perguntas

### (a) A recusa vem antes da autenticação

A rota da recusa roda antes de qualquer leitura do corpo, e por isso antes da autenticação.
Numa réplica sem vaga, um `POST /videos` sem token recebe `503`, e não `401`. O
`RecusaPorCapacidadeTest` fixa esse comportamento, e o contrato HTTP o registra como consequência
assumida.

- **Manter:** o que se protege é o volume, e a vaga é decidida sem ler o corpo de ninguém. Custa
  um cliente sem token ver `503` quando o erro dele é outro.
- **Mudar:** autenticar antes de decidir. É preciso descobrir se a autenticação do Quarkus roda
  antes da leitura do multipart, e se um envio não autenticado grava corpo no volume. Se gravar,
  a recusa passa a proteger menos.

### (b) O teto derivado do disco do host

Sem configuração, o teto de envios simultâneos é tamanho do volume / 200 MB. No Compose o volume
nomeado não tem tamanho próprio: é o disco do host. Nesta máquina deu **2354**, um número que
nunca vai valer como limite e que muda de máquina para máquina. Quem protege o volume na prática
é a conta do espaço livre com reserva.

- **Manter:** o critério de aceite do 108 ("derivado do tamanho do volume e do limite de 200 MB")
  está cumprido ao pé da letra, e a conta do espaço livre cobre o disco.
- **Trocar por orçamento declarado:** fixar um orçamento de disco para o volume de uploads, como o
  [ticket 011](011-limites-operacionais.md) fez com os 4 GB do scratch do `extracao`, e derivar o
  teto dele. Precisa decidir o número e se o orçamento é imposto (volume com tamanho) ou só
  declarado.

## Critérios de aceite

- [x] Decisão sobre (a), com o motivo, registrada no contrato HTTP.
- [x] Decisão sobre (b), com o número, se houver, e o motivo.
- [x] O que mudar no código vira ticket `ready-for-agent`, ou se resolve aqui se for só
      configuração.
- [x] Linha em "Decisões até aqui" no mapa.

## Resolução

Decidido em 2026-09-15, mantendo as duas escolhas do ticket 108 e sem mudança de código ou
configuração.

### (a) A recusa continua antes da autenticação

O contrato HTTP agora registra explicitamente que a ordem é deliberada. A rota precisa decidir
só com os cabeçalhos, antes de o multipart ser lido e ocupar o volume de uploads. Autenticar
antes poderia fazer o corpo de um envio sem token ser gravado antes da decisão e reduzir a
proteção que motivou a recusa. O preço aceito é um `POST` sem token receber `503` quando a
réplica não tem vaga, em vez de `401`; isso é consequência do recurso protegido e da ordem da
rota, não uma nova regra de autorização.

### (b) O default continua derivado, sem orçamento fixo de disco

O teto sem configuração continua sendo o tamanho do volume dividido pelo limite de 200 MB. O
valor **2354**, observado no host de 460 GB durante os tickets 108 e 115, é um dado daquela
máquina e não vira limite prometido pelo contrato. No Compose, o volume nomeado não tem cota
própria; declarar um orçamento sem impô-lo no volume daria uma precisão falsa, e impor uma cota
mudaria a implantação e exigiria uma decisão operacional separada.

A proteção efetiva continua sendo a conta do espaço livre, descontando as reservas dos envios em
andamento. Quando uma implantação precisar de um teto previsível, a propriedade
`fiapx.borda.teto-de-envios-simultaneos` já permite configurá-lo; não há alteração necessária
agora e nenhum novo ticket de código foi aberto.

O mapa registra as duas decisões em [Decisões até aqui](../map.md#decisões-até-aqui), e o contrato
HTTP registra a ordem da recusa e o caráter não normativo do valor observado.
