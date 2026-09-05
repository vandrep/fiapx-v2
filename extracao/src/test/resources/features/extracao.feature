# language: pt
Funcionalidade: Pipeline de extração de frames

  O `extracao` não tem borda HTTP: a borda dele é o RabbitMQ. Entra o comando `ExtrairVideo`
  pelo exchange `fiapx.comandos`, saem os eventos do contrato em `fiapx.eventos` e o Pacote no
  MinIO. Por isso os cenários publicam a mensagem no broker de verdade (Dev Services) e só
  observam o que atravessa essa borda — roteamento, desserialização e consumidor reais, nunca
  controller, use case ou gateway chamado direto.

  Cenário: Um vídeo válido produz os eventos de sucesso e um Pacote no bucket de destino
    Dado que o vídeo "video-valido.mp4" foi enviado para o MinIO
    Quando o comando de extração é publicado na fila do extracao
    Então o evento "extracao.iniciada" é publicado para o videos
    E o evento "extracao.iniciada" registra o instante em que o worker pegou o trabalho
    E o evento "extracao.concluida" é publicado para o videos
    E o Pacote é gravado no bucket de pacotes
    E o evento "extracao.concluida" declara o Pacote que foi gravado

  Cenário: Um arquivo que não é vídeo vira falha permanente, sem Pacote
    Dado que o arquivo "arquivo-invalido.txt" foi enviado para o MinIO como se fosse um vídeo
    Quando o comando de extração é publicado na fila do extracao
    Então o evento "extracao.iniciada" é publicado para o videos
    E o evento "extracao.falhou" é publicado para o videos com o motivo "ARQUIVO_INVALIDO"
    E nenhum Pacote é gravado no bucket de pacotes
