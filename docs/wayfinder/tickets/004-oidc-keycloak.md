# Keycloak bearer-only com quarkus-oidc

- id: 004
- label: wayfinder:research
- status: fechado
- assignee: agente de pesquisa (sessao de 2026-08-20)
- bloqueado-por:

## Question

A autenticação é Keycloak, com o serviço `videos` validando bearer tokens e derivando o
dono do Vídeo do `sub`. Levantar os fatos antes de desenhar realm e endpoints.

Investigar, contra a documentação oficial do Quarkus (3.31.3) e do Keycloak:

- Configuração `quarkus-oidc` em modo `service` (bearer-only): propriedades mínimas.
- Como acessar o `sub` e as claims dentro de um `Resource` sem contaminar o `core` com
  JWT — o template proíbe segurança fora de `framework.security`.
- `@RolesAllowed` com roles vindas do Keycloak: mapeamento de realm roles vs client roles.
- Como versionar um realm no repositório (`realm-export.json`) e importá-lo no start do
  container Keycloak, com usuários de demo já criados.
- Quarkus Dev Services for Keycloak: existe? sobe em `@QuarkusTest`? Como escrever teste
  de borda autenticado sem depender de um Keycloak externo?
- Como obter token por password grant via `curl` (o fluxo da demo, sem UI).

Registre os achados em `docs/pesquisa/oidc-keycloak.md`, com links para as fontes
primárias e configuração real.

## Resolução

Achados completos em [`docs/pesquisa/oidc-keycloak.md`](../../pesquisa/oidc-keycloak.md).

- **Acesso ao `sub` sem violar camadas**: o `ArchitectureConstraintsTest` proíbe
  `io.quarkus.*`, `org.eclipse.microprofile.*` e `jakarta.annotation.security.*` em `core`
  e `interfaces`. Logo `JsonWebToken`, `SecurityIdentity` e `@RolesAllowed` só existem em
  `framework.web`/`framework.security`. O Resource injeta `JsonWebToken` (direto ou via um
  `TokenSubjectProvider` `@ApplicationScoped`) e passa `jwt.getSubject()` como `String`
  pura ao controller, que a converte num value object do `core`.
- **Armadilha**: `getSubject()` é o `sub`, mas `getName()` vem de `upn`/`preferred_username`
  — e `quarkus.oidc.token.principal-claim` segue a mesma cadeia. Usar o principal como dono
  do Vídeo é um bug silencioso de autorização.
- **Dev Services for Keycloak existe e roda em `@QuarkusTest`**, desde que
  `quarkus.oidc.auth-server-url` só apareça sob perfil `%prod.` — configurá-la sem perfil
  desliga o Dev Service. `quarkus.keycloak.devservices.realm-path` aponta para o mesmo
  `realm-export.json` versionado que o Compose importa: fonte única do realm.
- **Teste de borda autenticado**: `io.quarkus:quarkus-test-keycloak-server` +
  `KeycloakTestClient.getAccessToken(...)` com RestAssured `.auth().oauth2(...)` — token
  real, assinatura real, `sub` real. `@TestSecurity`/`@OidcSecurity` fabrica identidade e
  não cobre esse cenário.
- **Roles**: realm role `usuario` dispensa `role-claim-path`; o Quarkus já lê
  `realm_access/roles` e `resource_access/<client>/roles` por default.
- **Password grant** exige `directAccessGrantsEnabled` no client público.
- **Health do Keycloak fica na porta 9000**, não 8080 — importa para o
  `depends_on: service_healthy` do Compose.

Duas pontas ficaram para o ticket de configuração do realm: se criar audience mapper (sem
ele, não configurar `token.audience`) e se o value object de dono valida formato UUID — o
que acoplaria o domínio ao Keycloak.
