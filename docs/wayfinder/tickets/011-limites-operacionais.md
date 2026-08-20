# Limites operacionais: tamanho, duração, formatos e retenção

- id: 011
- label: wayfinder:grilling
- status: aberto
- assignee:
- bloqueado-por:

## Question

Duas pesquisas convergiram no mesmo buraco. A de MinIO deixou cinco pontos em aberto na §6;
a de ffmpeg mediu que **fps=1 numa hora de 720p gera 3600 frames, entre 0,65 GB e 4,4 GB de
PNG**. Sem limites explícitos, um único upload derruba a demo — e é justamente o cenário que
um avaliador curioso vai testar.

A decidir:

- **Teto de upload**: qual valor para `quarkus.http.limits.max-body-size` (default de 10 MB
  é baixo demais, ilimitado é imprudente)? E o que o usuário recebe ao estourar?
- **Teto de duração ou de frames**: rejeitar na borda por duração via `ffprobe`, ou deixar
  processar e limitar o número de frames extraídos? Rejeitar cedo é honesto; limitar
  silenciosamente entrega um Pacote incompleto sem o usuário saber.
- **Formatos aceitos**: o original aceitava mp4/avi/mov/mkv/wmv/flv/webm por extensão do
  nome — o que não é validação. Validar por content-type, por `ffprobe`, ou aceitar tudo e
  deixar o exit 183 classificar como falha permanente?
- **Volume para o `uploads-directory`** do Vert.x: onde vive, quanto cabe, quem limpa.
- **Formato da chave de objeto no MinIO**: como identificar vídeo e Pacote, e se a chave
  carrega o dono (o que a torna adivinhável se algum dia houver presigned URL).
- **Retenção**: por quanto tempo o vídeo original e o Pacote sobrevivem, e quem apaga. O
  original apagava o vídeo após sucesso — mantemos isso?
- **Endpoint público do presigner**, se a decisão do contrato HTTP for por presigned URL.

Estes limites viram configuração e mensagens de erro, então alimentam diretamente o contrato
HTTP e a implementação do `extracao`.
