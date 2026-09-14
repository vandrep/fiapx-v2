# Diagramas e narração coerentes com o código depois da série do Vídeo perdido

- id: 111
- label: ready-for-agent
- status: aberto
- assignee:
- bloqueado-por: 105, 106, 107, 108
- prioridade: P3

## Origem

Revisão do [ticket 104](104-aceite-do-video-no-commit-da-linha.md), em 2026-09-13, sobre
`develop @ 7dd9065`. A revisão achou documentos que descrevem um sistema anterior ao código, e o
104 deixou esses pontos de fora de propósito. O mantenedor decidiu em 2026-09-14: um ticket só,
escopo restrito, e **bloqueado pelos 105–108**. Os quatro mudam o que os mesmos documentos
descrevem, e a correção deve acontecer numa passada só, depois deles.

## O que está desatualizado

Conferido contra o código em 2026-09-14:

1. **Diagrama do caminho feliz** (`docs/arquitetura.md`). O `202` aparece antes do `ExtrairVideo`
   e da marca. Desde o 104, a resposta sai depois do publish confirmado ou depois do teto de 2 s, e
   a marca pode chegar depois da resposta. O parágrafo logo abaixo cita "passo 4" e "passo 5"
   desse diagrama.
2. **Diagrama do caminho de falha e o texto que o acompanha** (`docs/arquitetura.md`). O diagrama
   mostra `UPDATE ... WHERE id = ? AND estado = 'PROCESSANDO'`, e o texto diz que "`FALHOU` só é
   alcançável a partir de `PROCESSANDO`" e cita `EstadoVideo.predecessor()`. Isso está velho desde
   o [ticket 027](027-melhorias-medidas.md): os estados terminais aceitam `RECEBIDO` **ou**
   `PROCESSANDO` ([ADR 0002](../../adr/0002-maquina-de-estados-em-duas-camadas.md)), e o método é
   `predecessores()`.
3. **Contagem de testes.** O `docs/arquitetura.md` diz "130 testes, 96 sem container". O
   `docs/roteiro-video.md` diz "cento e trinta" em duas tomadas, e numa delas também "noventa e
   seis". O mapa diz "144 (103 sem Docker)". A suíte da raiz tinha 470 testes em 2026-09-13.
4. **Narração do roteiro** (`docs/roteiro-video.md`). A tomada do passo 3 afirma que, no `202`, "o
   comando já está na fila". A tomada da sequência do caminho feliz cita "passo quatro" e "passo
   cinco" do diagrama do item 1.

Quando este ticket for pego, os 105–108 já terão mudado o sistema. Os dois diagramas e os trechos
acima devem bater com o código **daquele momento**, e não com esta lista. Se algum dos quatro já
tiver corrigido um item, basta registrar isso.

## Fora do escopo

- Varredura geral de coerência dos documentos. Só entram os dois diagramas, o texto em volta
  deles, as três contagens de testes e as tomadas do roteiro citadas acima.
- As limitações da conservação e a linha "Não perder requisição em pico", que são do
  [ticket 109](109-limitacoes-da-conservacao-no-documento-de-arquitetura.md). O 109 edita o mesmo
  `docs/arquitetura.md` em outra seção: parta do arquivo como ele estiver.
- Os três outros diagramas do `docs/arquitetura.md`.

## A restrição do roteiro

O roteiro é narração dublada com orçamento de **palavras**, não de segundos: ~145 palavras por
minuto, com teto por bloco escrito no cabeçalho de cada um ([ticket 024](024-roteiro-video.md)).
Corrigir uma frase não pode estourar o bloco. Se renumerar o diagrama mudar a narração, prefira
reescrever a frase para não depender do número do passo. Se não couber no bloco, pare e peça
decisão ao mantenedor, em vez de cortar outra tomada por conta própria.

## Critérios de aceite

- [ ] O diagrama do caminho feliz mostra a ordem real entre `INSERT`, publish, marca e `202`,
      incluindo o que acontece quando o publish não confirma dentro do teto.
- [ ] O diagrama do caminho de falha e o texto em volta mostram os predecessores reais do
      terminal e nomeiam o método que existe.
- [ ] Todo "passo N" citado em texto ou narração existe no diagrama correspondente.
- [ ] As três contagens de testes vêm de uma execução nova de `./mvnw test` a partir da raiz,
      com a data da execução registrada na resolução.
- [ ] O roteiro não afirma que o comando está na fila no `202`.
- [ ] A contagem de palavras de cada bloco alterado do roteiro continua dentro do teto do bloco, e
      os números do cabeçalho do bloco são atualizados.
- [ ] Linha em "Decisões até aqui" no mapa.
