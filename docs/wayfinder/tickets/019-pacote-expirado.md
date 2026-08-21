# O que a API responde quando o Pacote já expirou

- id: 019
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por:

## Question

Descoberto ao resolver o ticket 018: a tabela `video` **nunca perde linhas**, mas o ticket 011
fixou ciclo de vida de 7 dias no MinIO. No oitavo dia existe um Vídeo `CONCLUIDO`, listado
como tal pela API, cujo `chave_pacote` aponta para um objeto que não existe mais. O download
quebra de um jeito que o contrato HTTP do ticket 008 não prevê: lá o `409` significa "Pacote
indisponível" no sentido de *ainda não pronto*, não de *não existe mais*.

A decidir:

- **O que o `GET /videos/{id}/pacote` responde?** Reusar o `409` com um código de motivo novo
  é o caminho barato e provavelmente certo; `410 Gone` é semanticamente mais preciso e custa
  uma entrada a mais no contrato. `404` está errado — o Vídeo existe e é do usuário.
- **A listagem mente?** Um Vídeo `CONCLUIDO` cujo Pacote evaporou continua dizendo
  `CONCLUIDO`. Ou (a) aceita-se, e a verdade só aparece no download; ou (b) a representação de
  Vídeo ganha algo que distinga "tem Pacote" de "teve Pacote"; ou (c) um estado novo, que é
  caro — mexeria no `ck_video_estado`, no grafo do `core` e no ADR 0002.
- **Quem descobre a expiração?** Ninguém varre o MinIO; a descoberta é preguiçosa, no momento
  do download, quando o `ArquivoGateway` não acha o objeto. Isso é diferente de todo o resto
  do sistema, onde o Postgres é a autoridade — aqui o Postgres está desatualizado e só o
  adapter sabe.
- **Ou a retenção do ticket 011 é que está errada?** 7 dias foi escolhido para o Pacote e para
  o original pelo mesmo número. Se o Pacote nunca expirasse, o problema não existiria — ao
  preço de disco que uma demo não paga.

Este ticket precisa fechar **antes** do 016 escrever o `BaixarPacoteUseCase`.
