# language: pt
Funcionalidade: Pipeline de extração de frames

  O `extracao` não tem borda HTTP: o comportamento observável de fora é o que o pipeline faz
  com o MinIO, não uma resposta de API. Por isso os cenários exercitam o `ExtracaoController`
  — o mesmo papel de fronteira que um `Resource` cumpre num serviço com borda HTTP — com
  ffmpeg e MinIO reais (Dev Services), nunca dublês.

  Cenário: Um vídeo válido produz um Pacote no bucket de destino
    Dado que o vídeo "video-valido.mp4" foi enviado para o MinIO
    Quando o extracao processa o comando de extração para esse vídeo
    Então o processamento completa sem lançar exceção
    E o Pacote é gravado no bucket de pacotes

  Cenário: Um arquivo que não é vídeo não produz Pacote, mas ainda assim completa
    Dado que o arquivo "arquivo-invalido.txt" foi enviado para o MinIO como se fosse um vídeo
    Quando o extracao processa o comando de extração para esse vídeo
    Então o processamento completa sem lançar exceção
    E nenhum Pacote é gravado no bucket de pacotes
