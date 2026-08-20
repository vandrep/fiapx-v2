<!-- label: wayfinder:research -->
# Keycloak bearer-only com `quarkus-oidc`

> Pesquisa do ticket [`004-oidc-keycloak`](../wayfinder/tickets/004-oidc-keycloak.md).
> Alvo: Quarkus 3.31.3 / Java 21, serviço `videos` como **bearer-only**, dono do Vídeo
> derivado do claim `sub`. Fontes primárias: documentação oficial do Quarkus e do Keycloak
> (e o javadoc do MicroProfile JWT). Nenhuma paráfrase de blog.

## Fontes primárias consultadas

| Fonte | URL |
|---|---|
| Quarkus — OIDC Bearer Token Authentication | https://quarkus.io/guides/security-oidc-bearer-token-authentication |
| Quarkus — Dev Services for Keycloak | https://quarkus.io/guides/security-openid-connect-dev-services |
| Quarkus — OIDC configuration properties reference | https://quarkus.io/guides/security-oidc-configuration-properties-reference |
| Quarkus — Security Testing | https://quarkus.io/guides/security-testing |
| Keycloak — Importing and exporting realms | https://www.keycloak.org/server/importExport |
| Keycloak — Running Keycloak in a container | https://www.keycloak.org/server/containers |
| Keycloak — Securing applications with OpenID Connect (endpoints/direct grant) | https://www.keycloak.org/securing-apps/oidc-layers |
| MicroProfile JWT 2.1 — `JsonWebToken` javadoc | https://download.eclipse.org/microprofile/microprofile-jwt-auth-2.1/apidocs/org/eclipse/microprofile/jwt/JsonWebToken.html |

---

## 1. `quarkus-oidc` em modo `service` (bearer-only): propriedades mínimas

O guia de bearer token traz a configuração mínima:

```properties
quarkus.oidc.auth-server-url=http://localhost:8180/realms/quarkus
quarkus.oidc.client-id=quarkus-service-app
quarkus.oidc.application-type=service
```

Fatos da referência de propriedades:

- `quarkus.oidc.application-type` — **default já é `service`**; valores possíveis `web-app`,
  `service`, `hybrid`. Declarar explicitamente é documentação, não obrigação.
- `quarkus.oidc.auth-server-url` — URL base do provider (para Keycloak, `.../realms/<realm>`).
- `quarkus.oidc.client-id` — "identifies the OIDC client that requested the current bearer
  token". Em bearer-only **não é usado para autenticar o serviço**; serve para verificação de
  audiência e para o policy enforcer.
- `quarkus.oidc.discovery-enabled` — default `true`: o Quarkus lê
  `/.well-known/openid-configuration` do realm e descobre JWKS, issuer e endpoints.
- `quarkus.oidc.credentials.secret` — **não é necessário** em bearer-only puro (não há
  troca de código nem introspecção); só entra se o token for opaco ou se houver introspecção.
- `quarkus.oidc.token.issuer` / `quarkus.oidc.token.audience` — valores esperados de `iss` e
  `aud`; aceitam `any` para pular a verificação (default: nenhum valor configurado).
- `quarkus.oidc.tenant-enabled` — default `true`.

**Configuração proposta para `fiapx-videos`** (`src/main/resources/application.properties`):

```properties
# --- OIDC bearer-only ---
quarkus.oidc.application-type=service
quarkus.oidc.client-id=fiapx-videos
# Dev Services assume auth-server-url em dev/test; em prod o Compose manda o Keycloak real
%prod.quarkus.oidc.auth-server-url=http://keycloak:8080/realms/fiapx
# aud so vale checar se o realm tiver audience mapper apontando para fiapx-videos
%prod.quarkus.oidc.token.audience=fiapx-videos
```

> **Cuidado com `token.audience`**: o Keycloak **não** coloca o client-id do resource server
> no `aud` por padrão — é preciso um *audience mapper* no client scope. O guia do Quarkus
> descreve o padrão de alinhar `quarkus.oidc.token.audience` com `quarkus.oidc.client-id`;
> se o mapper não existir no realm exportado, a validação falha com 401. Ou cria-se o mapper,
> ou não se configura `token.audience`. **Decidir isso ao desenhar o realm.**

Dependência: extensão `quarkus-oidc` (grupo `io.quarkus`). Não é preciso `quarkus-smallrye-jwt`:
o `quarkus-oidc` já publica um `JsonWebToken` injetável para tokens JWT.

---

## 2. Acessar o `sub` sem contaminar `core` nem `interfaces`

### A restrição, literal

O `ArchitectureConstraintsTest` do template
(`src/test/java/com/example/app/architecture/ArchitectureConstraintsTest.java`) aplica a
`core` **e** a `interfaces` o regex:

```java
private static final Pattern FORBIDDEN_FRAMEWORK_IMPORT = Pattern.compile(
        "(?m)^import\\s+(io\\.quarkus|io\\.smallrye|jakarta\\.(annotation\\.security|enterprise|inject|persistence|ws\\.rs)|org\\.eclipse\\.microprofile)\\.");
```

Consequência direta e verificável por teste:

- `org.eclipse.microprofile.jwt.JsonWebToken` → **proibido** em `core` e em `interfaces`
  (`org.eclipse.microprofile.`).
- `io.quarkus.security.identity.SecurityIdentity` → **proibido** em `core` e em `interfaces`
  (`io.quarkus.`).
- `jakarta.annotation.security.RolesAllowed` → **proibido** em `core` e em `interfaces`.

Ou seja: **nenhum tipo de segurança atravessa a fronteira**. Só sobram `framework.web`
(Resources) e `framework.security`.

### O padrão que funciona

O `sub` é lido **no Resource** e desce como **dado puro** (`String`, ou melhor, um value
object do `core` do tipo `DonoId`). O `core` nunca sabe que existe um token.

O `JsonWebToken` injetado expõe o `sub` com semântica garantida pelo javadoc do MicroProfile:

- `getSubject()` — "The `sub` (Subject) claim identifies the principal that is the subject of
  the JWT. This is the token issuing IDP subject." → **é exatamente o `sub`**.
- `getName()` — "either comes from the `upn` claim, or if that is missing, the
  `preferred_username` claim." → **não é o `sub`**; nunca usar para dono do Vídeo.
- `getClaim(String)` — genérico, `null` se ausente.

Isso importa porque `SecurityIdentity.getPrincipal().getName()` e
`quarkus.oidc.token.principal-claim` (default: `upn`, senão `preferred_username`, senão `sub`)
**não são estáveis** como identificador de dono. Para derivar o dono, use `jwt.getSubject()`
— ou force `quarkus.oidc.token.principal-claim=sub` e documente a escolha.

Camada por camada:

```java
// framework.security — o unico lugar que conhece JWT
@ApplicationScoped
public class TokenSubjectProvider {           // nome sugerido
    @Inject JsonWebToken jwt;

    public String subject() {                 // devolve String pura
        var sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalStateException("token sem claim sub");
        }
        return sub;
    }
}
```

```java
// framework.web — Resource: unico ponto que junta HTTP + seguranca + controller
@Path("/videos")
public class VideoResource {

    @Inject VideoController videoController;
    @Inject TokenSubjectProvider tokenSubject;   // framework.security

    @POST
    @RolesAllowed("usuario")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @WithTransaction
    public Uni<Response> enviar(VideoController.EnviarRequest request) {
        return Uni.createFrom()
                .completionStage(videoController.enviar(tokenSubject.subject(), request))
                .map(id -> Response.status(Response.Status.CREATED).entity(id).build());
    }
}
```

```java
// interfaces.controllers — assinatura sem nenhum tipo de seguranca
public class VideoController {
    public CompletableFuture<UUID> enviar(String donoId, EnviarRequest request) {
        var command = new EnviarVideoUseCase.Command(new DonoId(donoId), request.nomeArquivo());
        return enviarVideoUseCase.executar(command);
    }
    public record EnviarRequest(String nomeArquivo) { }
}
```

Notas de projeto:

- O `DonoId` é value object do `core` (`core.entities`) — validação de formato mora nele.
- `TokenSubjectProvider` é `@ApplicationScoped` injetando `JsonWebToken`: o `quarkus-oidc`
  produz o `JsonWebToken` como bean `@RequestScoped` proxiado, então a leitura sempre vê o
  token da request corrente.
- Alternativa igualmente válida e ainda mais simples: injetar `JsonWebToken` direto no
  Resource (`framework.web`) e chamar `jwt.getSubject()` ali. O teste arquitetural permite,
  porque `framework.web` não está sob restrição. A classe em `framework.security` só se paga
  quando houver 2+ Resources ou regra extra (fallback de claim, normalização).
- **O que jamais fazer**: aceitar `donoId` vindo do corpo/query da request. O mapa já fixa
  isso — "dono do vídeo vem do `sub` do token, nunca do request".
- Consultas de listagem também filtram por `sub`: `GET /videos` chama
  `videoController.listarPorDono(tokenSubject.subject())`, e o download valida no use case
  que o dono do Pacote é o dono do token (retorno 404/403 pelo exception mapper).

---

## 3. `@RolesAllowed` com roles do Keycloak: realm roles vs client roles

Ordem de extração de roles documentada no guia de bearer token, na sequência:

1. `quarkus.oidc.roles.role-claim-path`, se configurado (ex.: `customroles`, `groups/roles`);
2. o claim `groups`, se presente;
3. os caminhos específicos do Keycloak: `realm_access/roles` **ou**
   `resource_access/<client_id>/roles`.

Ou seja, **para Keycloak funciona sem configuração nenhuma**: tanto realm roles quanto client
roles são reconhecidos por padrão. Propriedades relacionadas:

- `quarkus.oidc.roles.source` — default `accesstoken` para `application-type=service`
  (`idtoken` para `web-app`); valores `idtoken` | `accesstoken` | `userinfo`.
- `quarkus.oidc.roles.role-claim-path` — sem default; caminho separado por `/`.
- `quarkus.oidc.roles.role-claim-separator` — default: espaço.

Diferença prática dos dois tipos no token:

```json
{
  "sub": "3f2a...",
  "realm_access":    { "roles": ["usuario", "offline_access"] },
  "resource_access": { "fiapx-videos": { "roles": ["upload"] } }
}
```

- **Realm role** (`realm_access.roles`) — global ao realm, cai no token de qualquer client.
- **Client role** (`resource_access.<client>.roles`) — escopada ao client; só entra no token
  se o client estiver no escopo (default `full scope allowed`, ou via scope mappings).

**Recomendação para o FIAP X**: usar **realm role `usuario`**. É o caminho mais curto — os três
serviços (na prática só `videos` tem borda HTTP autenticada) enxergam a mesma role, o
`realm-export.json` fica menor, e não há dependência de scope mapping. `@RolesAllowed("usuario")`
funciona sem tocar em `role-claim-path`. Client roles só valem a pena se houver
permissionamento por serviço, o que o escopo do hackathon não pede.

**Onde a anotação pode aparecer**: `@RolesAllowed` é `jakarta.annotation.security.RolesAllowed`,
importe proibido em `core` e `interfaces` pelo teste arquitetural. Vive **apenas no Resource**,
o que o `AGENTS.md` já diz explicitamente ("usar anotações HTTP, `@RolesAllowed`, ... apenas aqui").

---

## 4. Versionar o realm e importar no start do container

### Exportar

Keycloak (`kc.sh export`), com a instância **parada**:

```
bin/kc.sh export --dir <dir>
bin/kc.sh export --file <file>
```

Ressalvas literais da doc:

- "consistency of an export is not guaranteed unless all Keycloak nodes are stopped prior to
  running the export";
- "Exported data does not include user and admin events, persisted sessions, workflow state
  or revoked tokens";
- o export pelo **Admin Console** *mascara* senhas e client secrets com asteriscos; o export
  pela **CLI não redige** esses dados. Como o realm da demo terá senhas de brinquedo
  (`user/user`), o export por CLI é o que serve — mas nunca reaproveite esse padrão em algo real.

### Importar no start

- `bin/kc.sh start --import-realm` (ou `start-dev --import-realm`);
- em container, o diretório de import é **`/opt/keycloak/data/import`**;
- "Only `.json` files are processed; sub-directories are ignored";
- placeholders de variável de ambiente são suportados dentro do arquivo de realm.

### Serviço no Docker Compose

```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.7.1
  command: ["start-dev", "--import-realm"]
  environment:
    KC_BOOTSTRAP_ADMIN_USERNAME: admin
    KC_BOOTSTRAP_ADMIN_PASSWORD: admin
    KC_HEALTH_ENABLED: "true"
  volumes:
    - ./infra/keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json:ro
  ports:
    - "8080:8080"
  healthcheck:
    test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000; echo -e 'GET /health/ready HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3; cat <&3 | grep -q '\"status\": \"UP\"'"]
    interval: 5s
    timeout: 5s
    retries: 30
```

Fatos que sustentam o trecho acima:

- imagem oficial `quay.io/keycloak/keycloak` (a doc de containers usa `:latest`; fixe a tag —
  o Dev Services do Quarkus 3.31.x usa `quay.io/keycloak/keycloak:26.7.1`, boa referência de
  versão compatível);
- `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` são os nomes atuais das
  variáveis de bootstrap do admin (as antigas `KEYCLOAK_ADMIN*` foram substituídas);
- `KC_HEALTH_ENABLED=true` expõe `/health`, `/health/ready` e `/health/live` na **porta de
  management 9000** — não na 8080. Por isso o healthcheck acima bate em `127.0.0.1:9000`;
  a imagem não traz `curl`, daí o truque com `/dev/tcp`. O mapa depende disso para o
  `depends_on: service_healthy`.

### Conteúdo mínimo do realm `fiapx`

| Objeto | Valor |
|---|---|
| Realm | `fiapx`, enabled |
| Realm role | `usuario` |
| Client `fiapx-web` (o da demo) | público, `directAccessGrantsEnabled: true`, `standardFlowEnabled: false`, `serviceAccountsEnabled: false` |
| Client `fiapx-videos` (o resource server) | bearer-only; existe para dar nome ao `aud` se houver audience mapper |
| Usuários de demo | `demo1` / `demo1`, `demo2` / `demo2`, ambos com a realm role `usuario` e `emailVerified: true`, e-mail válido (o serviço `notificacao` manda e-mail para o dono) |
| Default role | remover `offline_access`/`account` do escopo não é necessário; só não confie neles |

Dois usuários, não um: é o que permite demonstrar na banca que a listagem filtra por `sub`
e que `demo2` não vê o vídeo de `demo1`.

---

## 5. Dev Services for Keycloak: existe, e serve para teste de borda autenticado

**Existe.** É primeira classe no Quarkus e resolve exatamente o problema de "teste de borda
autenticado sem Keycloak externo".

### Como ativa

Três condições, segundo o guia: a extensão `quarkus-oidc` está presente, **`quarkus.oidc.auth-server-url`
não está configurado**, e o tenant OIDC default está habilitado. E, textualmente: "Dev Services
for Keycloak feature starts a Keycloak container for **both the dev and test modes**".

Isto é: **sobe em `@QuarkusTest`**. É por isso que a `auth-server-url` de produção precisa do
prefixo `%prod.` — o padrão que o próprio guia mostra:

```properties
%prod.quarkus.oidc.auth-server-url=http://localhost:8180/realms/quarkus
quarkus.keycloak.devservices.realm-path=quarkus-realm.json
```

Se a `auth-server-url` for declarada sem perfil, o Dev Services **não sobe** e os testes passam
a exigir um Keycloak de verdade. Esse é o erro de configuração mais provável neste projeto.

Pré-requisito: Docker/Podman disponível na máquina e no runner do CI (o GitHub Actions
`ubuntu-latest` tem Docker, então `./mvnw verify` roda).

### Realm: default ou o nosso

Sem arquivo de realm, o Dev Services cria: realm `quarkus`, client `quarkus-app` com secret
`secret`, usuários `alice` (roles `admin` e `user`) e `bob` (role `user`), senha igual ao
username. `quarkus.keycloak.devservices.create-realm` (default `true`) controla essa criação.

Com `quarkus.keycloak.devservices.realm-path` — "a comma-separated list of class or file system
paths to Keycloak realm files"; o primeiro arquivo inicializa as propriedades de conexão do
tenant default. **É o gancho que fecha o ciclo**: o mesmo `realm-export.json` versionado que o
Compose monta em `/opt/keycloak/data/import` é apontado aqui, e o teste roda contra o realm de
produção. Uma única fonte de verdade para o realm.

Também dá para injetar usuários sem editar o realm:

```properties
%dev.quarkus.keycloak.devservices.users.duke=dukePassword
%dev.quarkus.keycloak.devservices.roles.duke=reader
```

Outras propriedades: `enabled` (default `true`), `image-name` (default
`quay.io/keycloak/keycloak:26.7.1`), `shared` (default `true`, reúso de container por label),
`service-name` (default `keycloak`), `port`, `realm-name`, `disable-https`.

> Ressalva encontrada na doc de *Keycloak Authorization*: ali `realm-path` é descrito como
> efetivo "only in dev mode, not in JVM or native modes" — a advertência é sobre
> `@QuarkusIntegrationTest`/native (build já feito, Dev Services não reconfigura o artefato),
> não sobre `@QuarkusTest`. Para os testes Cucumber de borda, que são `@QuarkusTest`, vale.

### Escrevendo o teste de borda autenticado

**Opção A — token real, container real (recomendada para o Cucumber de borda).**
Dependência de teste `io.quarkus:quarkus-test-keycloak-server`, que fornece
`io.quarkus.test.keycloak.client.KeycloakTestClient`:

```java
@QuarkusTest
public class BearerTokenAuthenticationTest {

    KeycloakTestClient keycloakClient = new KeycloakTestClient();

    protected String getAccessToken(String userName) {
        return keycloakClient.getAccessToken(userName);   // senha == username no realm de dev
    }

    @Test
    public void testAdminAccess() {
        RestAssured.given().auth().oauth2(getAccessToken("alice"))
            .when().get("/api/admin")
            .then().statusCode(200);
    }
}
```

Esta é a única opção que exercita **de fato** a validação de assinatura, o `sub` real e o
mapeamento de roles do realm — que é justamente o que queremos testar. Nos steps do Cucumber,
um `Dado que estou autenticado como "demo1"` guarda o token e os passos seguintes fazem
`.auth().oauth2(token)`. E, porque o `sub` é gerado pelo Keycloak, o teste **não pode**
hard-codear o dono: guarde o `sub` decodificando o token ou compare vídeos entre `demo1` e
`demo2` (o cenário "demo2 não vê o vídeo de demo1" é o mais valioso e não precisa do valor).

**Opção B — sem container: `quarkus-test-security-oidc`.**

```java
@Test
@TestSecurity(user = "userOidc", roles = "viewer")
@OidcSecurity(claims = { @Claim(key = "email", value = "user@gmail.com") })
public void testOidcWithClaims() {
    RestAssured.when().get("test-endpoint").then().statusCode(200);
}
```

Rápido, mas **fabrica a identidade** em vez de validar um token — e o guia de testing avisa:
"The feature is only available for `@QuarkusTest` and will **not** work on a
`@QuarkusIntegrationTest`". Útil para testes unitários de Resource; **não** substitui o
cenário de borda.

**Opção C — `quarkus-test-oidc-server` (`OidcWiremockTestResource`)**: WireMock no lugar do
provider, tokens assinados por chave de teste. Sem Docker, mas também sem realm real. Sobra
para CI sem Docker — não é o nosso caso.

---

## 6. Obter token por password grant via `curl` (o fluxo da demo)

Endpoints do realm (doc de securing-apps):

- descoberta: `http://localhost:8080/realms/<realm>/.well-known/openid-configuration`
- token: `http://localhost:8080/realms/<realm>/protocol/openid-connect/token`

Exemplo literal da doc do Keycloak (client confidencial):

```bash
curl \
  -d "client_id=myclient" \
  -d "client_secret=40cc097b-2a57-4c17-b36a-8fdf3fc2d578" \
  -d "username=user" \
  -d "password=password" \
  -d "grant_type=password" \
  "http://localhost:8080/realms/master/protocol/openid-connect/token"
```

Para o FIAP X, com `fiapx-web` como **client público** (sem secret), é só omitir o
`client_secret`:

```bash
TOKEN=$(curl -s \
  -d "client_id=fiapx-web" \
  -d "username=demo1" \
  -d "password=demo1" \
  -d "grant_type=password" \
  "http://localhost:8080/realms/fiapx/protocol/openid-connect/token" \
  | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" \
  -F "arquivo=@video.mp4" \
  http://localhost:8081/videos

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/videos | jq
```

Inspecionar o `sub` que o serviço vai usar como dono (útil na demo e ao depurar):

```bash
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq '{sub, preferred_username, realm_access}'
```

Pré-requisitos no realm, senão o grant devolve `400 unauthorized_client`:

- o client `fiapx-web` precisa de `directAccessGrantsEnabled: true` (no Admin Console:
  *Direct access grants* marcado);
- o usuário precisa estar `enabled` e sem *required actions* pendentes (um
  `UPDATE_PASSWORD` pendente derruba o password grant).

> Aviso que a própria doc do Keycloak repete: o Resource Owner Password Credentials grant
> "MUST NOT be used" segundo as OAuth 2.0 Security Best Practices, e o Keycloak recomenda
> Device Authorization Grant ou Authorization Code. **Aqui é aceitável e deve ser dito em voz
> alta na documentação de arquitetura**: não há UI, o escopo é uma demo local, e a alternativa
> custaria um front-end que o mapa já colocou fora de escopo.

---

## Achados que viram decisão (para o mapa)

1. `application-type=service` já é o default — a config mínima real é `auth-server-url` +
   `client-id`, e mesmo o `client-id` só importa por causa de audiência.
2. `%prod.` na `auth-server-url` **não é estilo, é o que liga o Dev Services** em dev e test.
3. `realm-export.json` versionado serve aos dois lados: `/opt/keycloak/data/import` no Compose
   e `quarkus.keycloak.devservices.realm-path` nos testes. Fonte única.
4. `jwt.getSubject()` no Resource, `String` para dentro — `getName()` e `principal-claim`
   default apontam para `upn`/`preferred_username` e **não** servem como identidade do dono.
5. Realm role `usuario` em vez de client role: menos peças, zero configuração de
   `role-claim-path`.
6. Health do Keycloak vive na porta **9000**, não na 8080 — o `depends_on: service_healthy`
   do Compose depende de acertar isso.
7. Dois usuários de demo, não um: sem `demo2` não há como demonstrar o isolamento por `sub`.

## Pontas soltas

- **Audience mapper**: decidir no ticket do realm se `fiapx-videos` entra no `aud`. Se sim,
  configurar `quarkus.oidc.token.audience`; se não, deixar a propriedade fora.
- **Formato do `sub`**: é um UUID no Keycloak, mas o contrato é "string opaca". Se `DonoId`
  validar UUID, o serviço fica preso ao Keycloak — decidir ao modelar o `core`.
- **Swagger UI com bearer**: `quarkus.swagger-ui.always-include=true` já está no template; falta
  checar se vale expor o botão *Authorize* (`quarkus.smallrye-openapi.security-scheme=jwt`) para
  não obrigar o `curl` na demo.
