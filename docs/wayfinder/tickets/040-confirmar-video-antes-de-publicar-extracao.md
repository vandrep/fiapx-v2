# Confirmar a persistência do Vídeo antes de publicar a Extração

- id: 040
- label: wayfinder:bug
- status: aberto
- assignee:
- bloqueado-por:
- prioridade: P1

## Origem e evidência

Achado do eixo Spec da revisão de `3a3ec95...f6baade`, convertido em ticket com aprovação
do usuário. A corrida foi identificada pela leitura do fluxo e ainda precisa de reprodução
controlada: a transação do envio engloba a persistência, a publicação de `ExtrairVideo` e a
marca de publicação. Os consumidores de eventos confirmam a mensagem quando não encontram
o Vídeo. Se a Extração terminar antes do commit, seus eventos podem ser descartados e o
envio depois confirmar um Vídeo em `RECEBIDO` com a marca preenchida, fora da reconciliação.

## O que entregar

Um Vídeo aceito deve estar persistido e visível aos consumidores antes de o comando de
Extração ser publicado. Eventos rápidos precisam produzir o desfecho observável pela API,
e uma interrupção entre a persistência e a publicação deve continuar recuperável pela
reconciliação. Preservar as decisões dos ADRs 0001, 0002 e 0003 e a resposta de envio do
contrato HTTP.

## Condições de aceite

- [ ] Reproduzir a corrida com sincronização controlada, sem depender de sleeps ou sorte,
  demonstrando a confirmação de evento antes de o Vídeo estar visível no banco.
- [x] Garantir commit da persistência antes da publicação do comando; apenas flush não
  satisfaz a visibilidade por outra transação.
- [ ] Verificar pela borda que eventos de Extração rápida, incluindo falha permanente,
  não deixam um Vídeo aceito sem desfecho por ausência da linha no consumo.
- [ ] Interromper o caminho entre commit e publicação e comprovar que a reconciliação
  recupera a pendência; falha de publicação não pode deixar marca de sucesso.
- [x] Executar a suíte a partir da raiz, o smoke e o ensaio de conservação aplicável à
  reconciliação, registrando resultados e eventuais falhas preexistentes separadamente.

## Dependências

Nenhuma. Pode começar imediatamente; os testes desta correção pertencem a este ticket.

## Progresso

O commit `7f78e45` fez `VideoDataSourceAdapter.adicionar` abrir sua própria
transação, e `VideosResource` não mantém mais uma transação que englobe a publicação do
comando. Portanto, a conclusão da persistência no encadeamento de envio significa commit, não
somente flush.

O teste de integração `confirmacaoDeEventoRapidoEnxergaOVideoEProduzFalha` usa Postgres real e
um consumidor controlado: a confirmação de `ExtrairVideo` consulta a linha e processa uma
`ExtracaoFalhou` permanente antes do caminho de envio terminar; a linha termina em `FALHOU`.
`publicacaoInterrompidaMantemComandoPendenteParaAProximaVarredura` injeta a falha de publicação,
exige marca nula e prova a republicação posterior pela reconciliação.

Essas provas ainda não fecham três aceites: não reproduzem a corrida pré-correção com a linha
invisível, não observam o evento rápido pela API HTTP e não interrompem a janela real entre o
commit inicial e a primeira publicação. O ticket permanece aberto para cobrir essas três
fronteiras.

Validação: `./mvnw test` na raiz passou. O smoke foi reexecutado integralmente e passou, com
Vídeo válido `CONCLUIDO`, Pacote íntegro, falha permanente esperada e e-mail. A observação
anterior de `ARQUIVO_INVALIDO` era a etapa deliberadamente inválida do próprio smoke, e não uma
falha do fixture válido. O ensaio `mata-videos` aceitou 12 Vídeos antes da queda e terminou
12/12 em `CONCLUIDO`, sem presos ou `FALHOU`; o critério de amostra pela API reprovou por
`jq: parse error: Invalid numeric literal` após o censo já verde. A causa desse erro permanece
pendente de diagnóstico.
