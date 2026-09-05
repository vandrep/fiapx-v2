# language: pt
Funcionalidade: Borda pública do serviço videos

  Sem interface web, este contrato é o produto: é o que a banca vê no Swagger UI e o que o
  script de smoke exercita. Toda operação é escopada pelo `sub` do token — não existe
  parâmetro de usuário em lugar nenhum.

  Contexto:
    Dado que estou autenticado como "demo"

  Cenário: Enviar um vídeo devolve 202 e o Vídeo em RECEBIDO
    Quando eu envio o arquivo "ferias.mp4" com content-type "video/mp4"
    Então a resposta tem status 202
    E o cabeçalho "Location" aponta para o Vídeo criado
    E o campo "estado" da resposta é "RECEBIDO"
    E o campo "nome" da resposta é "ferias.mp4"
    E o campo "concluidoEm" da resposta é nulo
    E o campo "motivo" da resposta é nulo
    E o corpo da resposta tem só os sete campos públicos

  Cenário: Extensão fora da lista é recusada com 415
    Quando eu envio o arquivo "relatorio.pdf" com content-type "video/mp4"
    Então a resposta tem status 415
    E a resposta é um problem+json com título "Formato nao suportado"

  Cenário: Content-type fora de video/* é recusado com 415
    Quando eu envio o arquivo "ferias.mp4" com content-type "application/pdf"
    Então a resposta tem status 415
    E a resposta é um problem+json com título "Formato nao suportado"

  Cenário: Envio sem o campo arquivo é recusado com 400
    Quando eu envio uma requisição multipart sem o campo arquivo
    Então a resposta tem status 400
    E a resposta é um problem+json com título "Requisicao invalida"

  Cenário: Consultar um Vídeo próprio
    Dado que enviei o arquivo "ferias.mp4"
    Quando eu consulto o Vídeo enviado
    Então a resposta tem status 200
    E o campo "estado" da resposta é "RECEBIDO"

  Cenário: Vídeo de outro usuário responde o mesmo 404 de id inexistente
    Dado que enviei o arquivo "ferias.mp4"
    Quando eu me autentico como "outro"
    E eu consulto o Vídeo enviado
    Então a resposta tem status 404
    E a resposta é um problem+json com título "Video nao encontrado"

  Cenário: Id inexistente responde 404
    Quando eu consulto um Vídeo que não existe
    Então a resposta tem status 404

  Cenário: A listagem só enxerga os Vídeos do dono
    Dado que enviei o arquivo "meu-1.mp4"
    E que enviei o arquivo "meu-2.mp4"
    E que o usuário "outro" enviou o arquivo "dele.mp4"
    Quando eu listo os meus Vídeos
    Então a resposta tem status 200
    E a listagem tem 2 itens e total 2
    E a listagem traz os Vídeos "meu-2.mp4, meu-1.mp4" nessa ordem

  Cenário: O filtro por estado corta o conteúdo e o total juntos
    Dado que enviei o arquivo "pendente-1.mp4"
    E que enviei o arquivo "pendente-2.mp4"
    E que enviei o arquivo "pronto.mp4"
    E que a Extração do Vídeo concluiu com um Pacote de 2048 bytes
    Quando eu listo os meus Vídeos no estado "CONCLUIDO"
    Então a resposta tem status 200
    E a listagem tem 1 itens e total 1
    E a listagem traz os Vídeos "pronto.mp4" nessa ordem
    Quando eu listo os meus Vídeos no estado "RECEBIDO" com tamanho 1
    Então a resposta tem status 200
    E a listagem tem 1 itens e total 2
    E a listagem traz os Vídeos "pendente-2.mp4" nessa ordem
    Quando eu listo os meus Vídeos no estado "FALHOU"
    Então a resposta tem status 200
    E a listagem tem 0 itens e total 0

  Cenário: A listagem pagina, e o total é o da consulta inteira
    Dado que enviei o arquivo "um.mp4"
    E que enviei o arquivo "dois.mp4"
    E que enviei o arquivo "tres.mp4"
    Quando eu listo os meus Vídeos com página 0 e tamanho 2
    Então a resposta tem status 200
    E a listagem tem 2 itens e total 3
    E a listagem está na página 0 com tamanho 2
    E a listagem traz os Vídeos "tres.mp4, dois.mp4" nessa ordem
    Quando eu listo os meus Vídeos com página 1 e tamanho 2
    Então a resposta tem status 200
    E a listagem tem 1 itens e total 3
    E a listagem está na página 1 com tamanho 2
    E a listagem traz os Vídeos "um.mp4" nessa ordem

  Cenário: O item da listagem é a mesma representação da consulta individual
    O contrato publica uma representação de Vídeo só. O Vídeo CONCLUIDO é onde ela tem mais
    o que vazar: chave de Pacote, contagem de frames e tamanho do Pacote existem no banco e
    não estão no contrato.

    Dado que enviei o arquivo "ferias.mp4"
    E que a Extração do Vídeo concluiu com um Pacote de 2048 bytes
    Quando eu listo os meus Vídeos
    Então a resposta tem status 200
    E o item do Vídeo enviado é igual à consulta individual
    E o item do Vídeo enviado tem só os sete campos públicos

  Cenário: Um Vídeo FALHOU publica o motivo como código, na consulta e na listagem
    O `motivo` é o campo mais frágil da representação: ele sai como código, nunca como
    frase — a frase que o usuário lê é do `notificacao`.

    Dado que enviei o arquivo "quebrado.mkv"
    E que a Extração do Vídeo falhou por "SEM_FLUXO_DE_VIDEO"
    Quando eu consulto o Vídeo enviado
    Então a resposta tem status 200
    E o campo "estado" da resposta é "FALHOU"
    E o campo "motivo" da resposta é "SEM_FLUXO_DE_VIDEO"
    E o corpo da resposta tem só os sete campos públicos
    Quando eu listo os meus Vídeos
    Então a resposta tem status 200
    E o item do Vídeo enviado é igual à consulta individual

  Cenário: Baixar o Pacote de um Vídeo que ainda não concluiu é 409, ainda não
    Dado que enviei o arquivo "ferias.mp4"
    Quando eu baixo o Pacote do Vídeo
    Então a resposta tem status 409
    E a resposta é um problem+json com título "Pacote indisponivel"

  Cenário: Baixar o Pacote de um Vídeo CONCLUIDO devolve o zip em streaming
    Dado que enviei o arquivo "ferias.mp4"
    E que a Extração do Vídeo concluiu com um Pacote de 2048 bytes
    Quando eu baixo o Pacote do Vídeo
    Então a resposta tem status 200
    E o corpo da resposta tem 2048 bytes
    E o cabeçalho "Content-Disposition" contém "ferias.zip"

  Cenário: Pacote expirado no armazenamento é 410, não mais
    Dado que enviei o arquivo "ferias.mp4"
    E que a Extração do Vídeo concluiu com um Pacote de 2048 bytes
    E que o Pacote sumiu do armazenamento
    Quando eu baixo o Pacote do Vídeo
    Então a resposta tem status 410
    E a resposta é um problem+json com título "Pacote expirado"
    Quando eu consulto o Vídeo enviado
    Então a resposta tem status 200
    E o campo "estado" da resposta é "CONCLUIDO"
    Quando eu baixo o Pacote do Vídeo
    Então a resposta tem status 410

  Cenário: Sem token não se entra
    Quando eu listo os meus Vídeos sem autenticação
    Então a resposta tem status 401
