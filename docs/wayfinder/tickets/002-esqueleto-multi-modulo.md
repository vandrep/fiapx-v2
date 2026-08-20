# Esqueleto Maven multi-módulo a partir do template

- id: 002
- label: wayfinder:prototype
- status: aberto
- assignee:
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
