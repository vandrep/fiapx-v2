# Decisões que a recusa por capacidade deixou para o mantenedor

- id: 116
- label: ready-for-human
- status: aberto
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

- [ ] Decisão sobre (a), com o motivo, registrada no contrato HTTP.
- [ ] Decisão sobre (b), com o número, se houver, e o motivo.
- [ ] O que mudar no código vira ticket `ready-for-agent`, ou se resolve aqui se for só
      configuração.
- [ ] Linha em "Decisões até aqui" no mapa.
