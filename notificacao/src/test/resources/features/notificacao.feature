# language: pt
Funcionalidade: Notificação de falha por e-mail

  O `notificacao` não tem borda HTTP: a borda dele é o RabbitMQ. Entra o evento `VideoFalhou`
  publicado pelo `videos` em `fiapx.eventos`, sai um e-mail. Por isso os cenários publicam a
  mensagem no broker de verdade (Dev Services) e só observam o e-mail que sai — roteamento,
  desserialização e consumidor reais, nunca controller, use case ou gateway chamado direto.

  Cenário: Um motivo conhecido gera e-mail com a frase traduzida
    Dado que o vídeo "ferias-2026.mp4" falhou com o motivo "FORMATO_NAO_SUPORTADO" para "dono@example.com"
    Quando o videos publica o evento VideoFalhou
    Então um e-mail é enviado para "dono@example.com"
    E o assunto do e-mail menciona "ferias-2026.mp4"
    E o corpo do e-mail contém "não é suportado"
    E o e-mail não expõe o código técnico "FORMATO_NAO_SUPORTADO"

  Cenário: Um motivo desconhecido ainda assim gera e-mail, com frase genérica
    # DESCONHECIDO é o que o `videos` de fato põe no fio quando o `extracao` manda um código
    # que ele não reconhece: ele pousa o código no próprio enum e publica `motivo.name()`,
    # nunca o texto cru. A tolerância a uma string fora do enum é do `MotivoFalhaTest`.
    Dado que o vídeo "clipe.mp4" falhou com o motivo "DESCONHECIDO" para "outro@example.com"
    Quando o videos publica o evento VideoFalhou
    Então um e-mail é enviado para "outro@example.com"
    E o assunto do e-mail menciona "clipe.mp4"
    E o corpo do e-mail contém "não foi possível determinar"
    E o e-mail não expõe o código técnico "DESCONHECIDO"
