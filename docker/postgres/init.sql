-- Script de criacao do banco de dados do FIAP X.
--
-- Existe UM database em todo o sistema, `fiapx_videos`, porque so o servico `videos` e
-- dono de estado: `extracao` e worker sem estado e `notificacao` nao persiste nada (a
-- guarda de unicidade do e-mail e a propria transicao de estado do Video, ADR 0001).
--
-- O database em si e criado pelo `POSTGRES_DB` da imagem do Postgres, que roda antes dos
-- scripts de `docker-entrypoint-initdb.d`; este arquivo cuida apenas do esquema.
--
-- Em `%prod` a aplicacao sobe com `quarkus.hibernate-orm.schema-management.strategy=validate`,
-- entao divergencia entre este script e as entidades derruba o servico no boot. Este
-- arquivo e verificado, nao e documentacao.
--
-- Decidido no ticket 009 (docs/wayfinder/tickets/009-modelo-dominio-videos.md); as marcas de
-- publicacao e seus indices parciais vieram do ticket 018 (docs/adr/0003-reconciliacao-por-varredura.md).

CREATE TABLE video (
    -- UUID gerado pelo `videos` no upload; correlaciona todas as mensagens do sistema.
    id                   UUID         NOT NULL,

    -- Dono: vem do token, nunca do request. `email` e carga (o `VideoFalhou` precisa
    -- dele), `sub` e identidade — e so ele participa dos predicados de consulta.
    dono_sub             VARCHAR(255) NOT NULL,
    email_dono           VARCHAR(320) NOT NULL,

    -- Nome do arquivo original: sem ele a listagem e uma coluna de UUIDs e o e-mail de
    -- falha nao diz qual video falhou.
    nome                 VARCHAR(255) NOT NULL,
    tamanho_bytes        BIGINT       NOT NULL,

    estado               VARCHAR(20)  NOT NULL,
    recebido_em          TIMESTAMPTZ  NOT NULL,

    -- Instante terminal, de CONCLUIDO ou de FALHOU. Os dois estados sao mutuamente
    -- exclusivos, entao uma coluna basta; sai como `concluidoEm` no contrato HTTP.
    finalizado_em        TIMESTAMPTZ,

    -- Referencias opacas ao MinIO. O dominio guarda a chave, o adapter e quem conhece a
    -- convencao de nomes (formato definido no ticket 011).
    chave_video          VARCHAR(1024) NOT NULL,
    chave_pacote         VARCHAR(1024),

    -- Resultado da Extracao. `quantidade_frames` nao aparece na API: e a unica prova
    -- numerica de que a Extracao funcionou, e chega de graca no ExtracaoConcluida.
    quantidade_frames    INTEGER,
    tamanho_pacote_bytes BIGINT,

    -- Codigo estavel, nunca frase: a traducao para o usuario e do `notificacao`.
    -- DESCONHECIDO existe para o versionamento aditivo — um codigo novo publicado por um
    -- `extracao` mais recente nao pode derrubar o consumidor.
    motivo               VARCHAR(30),

    -- Marcas de publicacao: a tabela `video` E o outbox (ADR 0003). Nulo significa "esta
    -- mensagem ainda nao saiu daqui", e e o que a varredura de reconciliacao procura.
    -- Gravadas DEPOIS do publish, de proposito: crash entre os dois republica (inofensivo,
    -- consumo idempotente), enquanto marcar antes perderia a mensagem de vez.
    -- Nao se chamam `notificado_em`: o `videos` nao sabe se o e-mail chegou, so sabe que
    -- publicou.
    comando_publicado_em TIMESTAMPTZ,
    falha_publicada_em   TIMESTAMPTZ,

    CONSTRAINT pk_video PRIMARY KEY (id),

    CONSTRAINT ck_video_estado CHECK (
        estado IN ('RECEBIDO', 'PROCESSANDO', 'CONCLUIDO', 'FALHOU')
    ),
    CONSTRAINT ck_video_motivo CHECK (
        motivo IS NULL OR motivo IN (
            'ARQUIVO_INVALIDO',
            'FORMATO_NAO_SUPORTADO',
            'SEM_FLUXO_DE_VIDEO',
            'DURACAO_EXCEDIDA',
            'TENTATIVAS_ESGOTADAS',
            'DESCONHECIDO'
        )
    )
);

-- Indice da listagem. A listagem e sempre `WHERE dono_sub = ?` com
-- `ORDER BY recebido_em DESC`; o filtro opcional por `estado` tem quatro valores
-- possiveis e nao merece coluna no indice — ajudaria so o caso filtrado e atrapalharia o
-- default da API.
CREATE INDEX ix_video_dono_recebido ON video (dono_sub, recebido_em DESC);

-- Indices da varredura de reconciliacao (ADR 0003). Ela roda a cada 30s sobre uma tabela
-- que nunca perde linhas, e o indice acima nao serve: o predicado nao tem `dono_sub`.
-- Parciais porque o predicado e quase sempre falso — custam quase nada em disco e em
-- escrita.
CREATE INDEX ix_video_comando_pendente
    ON video (recebido_em)
    WHERE comando_publicado_em IS NULL;

-- O `estado` no predicado nao e decoracao: toda linha nao-falhada tem `falha_publicada_em`
-- nula, entao sem ele este indice parcial indexaria a tabela inteira.
CREATE INDEX ix_video_falha_pendente
    ON video (finalizado_em)
    WHERE estado = 'FALHOU' AND falha_publicada_em IS NULL;
