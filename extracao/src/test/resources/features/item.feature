# language: pt
Funcionalidade: Cadastro de itens
  Como usuaria do catalogo
  Quero cadastrar e consultar itens
  Para manter o catalogo do modulo atualizado

  Cenario: Cadastrar um item e consulta-lo em seguida
    Quando eu cadastro um item com o nome "Chave de fenda"
    Entao a busca pelo item cadastrado retorna o nome "Chave de fenda"

  Cenario: Buscar um item que nao existe
    Quando eu busco um item com um id que nao existe
    Entao a busca retorna que o item nao foi encontrado
