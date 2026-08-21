# Esqueleto Maven multi-módulo a partir do template

- id: 002
- label: wayfinder:prototype
- status: fechado
- assignee: vandrep (sessao de 2026-08-21)
- bloqueado-por: 001

## Question

O template é **single-module**: `scripts/init-project.sh` e `ArchitectureConstraintsTest`
assumem um `pom.xml` e um `src/` só. Precisamos de três serviços deployáveis
independentemente no mesmo repositório.

Como fica a estrutura concretamente?

- O parent agregador gera artefato ou é só `pom`? Onde ficam as propriedades
  `java.version` / `quarkus.platform.version`?
- Rodar `init-project.sh` uma vez por serviço em diretórios separados e depois costurar o
  parent, ou adaptar o script para gerar multi-módulo?
- `ArchitectureConstraintsTest` roda por módulo (uma cópia em cada) ou uma vez no parent
  varrendo todos? A regra "um módulo de negócio por serviço" muda de sentido quando cada
  serviço tem um único módulo — o teste precisa ser reescrito ou só reapontado?
- Um `Dockerfile` por serviço: onde vive e como referencia o build do módulo?
- `./mvnw test` na raiz roda os três?

Construa o esqueleto de verdade (os três módulos compilando, com o módulo de exemplo
ainda dentro, sem lógica de domínio) e reaja a ele. O artefato é descartável até a
conversa fechar — mas se ele ficar bom, vira a base do projeto.

## Resolução

O esqueleto foi construído de verdade, os três módulos compilam e `./mvnw test` na raiz
passa nos três. Fonte primária: branch descartável `prototype/esqueleto-multi-modulo`
(commit `c158c04`), adotado como base do projeto pelo merge `94ddd54`.

### Estrutura

```
pom.xml            parent agregador, packaging pom, br.com.fiapx:fiapx
mvnw, .mvn/        wrapper na raiz, um só
videos/            pom + src + Dockerfile
extracao/          idem
notificacao/       idem
```

O parent **não gera artefato deployável**. Ele carrega `java.version` e
`quarkus.platform.version`, o BOM do Quarkus em `dependencyManagement`, os plugins
compiler/surefire/failsafe em `<plugins>`, e o `quarkus-maven-plugin` em
`pluginManagement` — cada filho o declara sem versão. Em `<plugins>` do parent o plugin
tentaria rodar sobre o próprio pom agregador.

Dependências no parent são só as verdadeiramente comuns: `quarkus-arc`,
`quarkus-smallrye-health` e o ferramental de teste (JUnit, RestAssured, Cucumber).
Extensão de negócio fica no pom de cada serviço — e isso já morde: **só `videos` tem
Panache e Postgres**. `extracao` e `notificacao` perderam `framework/db` e ganharam um
`ItemMemoriaAdapter` placeholder, porque serviço sem banco não pode carregar
`@WithTransaction`.

### Pacote base: `br.com.fiapx`, módulo homônimo do serviço

`scripts/init-project.sh` rodou **uma vez por serviço**, com `--target-dir`, **sem
nenhuma edição no script**:

```bash
./scripts/init-project.sh --app-name fiapx-videos --package br.com.fiapx \
    --modules videos --target-dir /tmp/gen-videos
```

O pacote base é `br.com.fiapx`, não `br.com.fiapx.<servico>`, e o módulo de negócio é
**homônimo do serviço**. Assim `br.com.fiapx.videos.core` mantém a regra
`<base>.<modulo>.<camada>` do template intacta, sem o redundante `videos.videos`. Isso
corrige a linha "Package base" da tabela de restrições do mapa.

### `ArchitectureConstraintsTest`: reapontado, não reescrito

Uma cópia idêntica por módulo. `MAIN_SOURCES` é relativo ao CWD, que no surefire é o
basedir do módulo — então cada cópia varre só o seu próprio `src/main/java`, sem
configuração adicional. Duas mudanças reais no teste:

1. Constante `MODULO_DO_SERVICO` e a asserção de que o serviço contém **exatamente um**
   módulo de negócio, homônimo. Verificado: plantar `br.com.fiapx.intruso.core` em
   `extracao` quebra o build com *"deve conter exatamente o modulo de negocio extracao,
   mas contem [extracao, intruso]"*.
2. As guardas `assertFalse(...isEmpty(), "Nenhum resource/DataSourceAdapter foi
   encontrado")` viraram opcionais. Foram escritas para um monólito onde toda camada está
   sempre populada; **por serviço são falhas falsas** — `notificacao` não tem borda HTTP e
   nenhum dos dois workers tem persistência. As guardas de core, controller e use case
   continuam duras.

As três cópias de ~330 linhas são o preço de não ter módulo `shared` (fora de escopo).
Aceito conscientemente; extrair um módulo `test-support` se doer.

### Dockerfile

Um por serviço, na raiz do módulo, **single-stage sobre o `target/quarkus-app` já
empacotado** — contexto de build é o diretório do serviço. Uma única compilação Maven
serve as três imagens, o que é o que importa para o CI. Multi-stage (contexto na raiz,
build dentro da imagem) custaria três compilações completas e foi descartado.

`extracao` acrescenta `apk add --no-cache ffmpeg` sobre `eclipse-temurin:21-jre-alpine`,
conforme o ticket 006. Medido: `videos` 382 MB, `extracao` 509 MB, `notificacao` 327 MB.
Verificado subindo a imagem: `/q/health/ready` responde UP, `POST /itens` funciona,
`ffmpeg 8.0.1` presente em `extracao` e ausente nos outros dois.

### Fatos de ambiente

- **Não há JDK 21 nesta máquina.** `/usr/lib/jvm/java-21-openjdk-amd64` é um JRE, sem
  `javac`. O build roda com o JDK 25 (`~/.jbang/cache/jdks/25`) compilando `--release 21`.
  O CI usa `setup-java` com temurin 21 e não sofre disso.
- `./mvnw test` na raiz **exige Docker de pé**: `videos` sobe Dev Services do Postgres.

### Ficou de fora, virou ticket

`AGENTS.md` na raiz não foi escrito. As regras de camada do template continuam valendo,
mas as duas mudanças no teste arquitetural precisam estar documentadas para quem (agente
ou humano) for escrever código depois. Virou o ticket 012.
