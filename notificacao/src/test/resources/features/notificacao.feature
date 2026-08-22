# language: pt
Funcionalidade: Notificação de falha por e-mail

  O `notificacao` não tem borda HTTP: o comportamento observável de fora é o e-mail que ele
  manda, não uma resposta de API. Por isso os cenários exercitam o `NotificacaoController` —
  o mesmo papel de fronteira que um `Resource` cumpre num serviço com borda HTTP.

  Cenário: Um motivo conhecido gera e-mail com a frase traduzida
    Dado que um vídeo "ferias-2026.mp4" falhou com o motivo "FORMATO_NAO_SUPORTADO" para "dono@example.com"
    Quando o notificacao processa esse evento
    Então um e-mail é enviado para "dono@example.com"
    E o assunto do e-mail menciona "ferias-2026.mp4"
    E o corpo do e-mail contém "não é suportado"

  Cenário: Um motivo desconhecido ainda assim gera e-mail, com frase genérica
    Dado que um vídeo "clipe.mp4" falhou com o motivo "CODIGO_DE_UM_EXTRACAO_MAIS_NOVO" para "outro@example.com"
    Quando o notificacao processa esse evento
    Então um e-mail é enviado para "outro@example.com"
    E o corpo do e-mail contém "não foi possível determinar"
