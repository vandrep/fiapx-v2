package br.com.fiapx.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trava, via build, as regras arquiteturais descritas em AGENTS.md. Nao adiciona nenhuma
 * dependencia extra (ArchUnit, etc.) de proposito: le os fontes e valida convencoes de
 * pacote, assinatura e anotacao com regex simples. Veja AGENTS.md para a explicacao de
 * cada regra e docs/templates/monolito-modular no repositorio de origem deste template.
 */
class ArchitectureConstraintsTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path CONFIG_PROPERTIES = Path.of("src/main/resources/application.properties");
    private static final String BASE_PACKAGE = "br.com.fiapx";
    /**
     * Cada servico deployavel carrega exatamente um modulo de negocio, homonimo do servico.
     * Derivado do nome do diretorio do modulo, e nao fixado: o CWD do surefire e o basedir,
     * o mesmo pressuposto de MAIN_SOURCES logo acima. Isso mantem as tres copias deste
     * arquivo byte a byte identicas, o que o script scripts/verifica-testes-arquiteturais.sh
     * cobra no build. Ver AGENTS.md.
     */
    private static final String MODULO_DO_SERVICO = Path.of("").toAbsolutePath().getFileName().toString();
    private static final Set<String> REQUIRED_MODULE_LAYERS = Set.of("core", "interfaces", "framework");
    private static final Set<String> SHARED_PACKAGES_WITHOUT_LAYER = Set.of(
            BASE_PACKAGE + ".common",
            BASE_PACKAGE + ".common.web");

    // Adicione aqui, nominalmente, arquivos que precisem violar uma regra por razao explicita
    // e documentada. Em projetos novos, mantenha vazio.
    private static final Set<String> CORE_FRAMEWORK_IMPORT_EXCEPTIONS = Set.of();
    private static final Set<String> INTERFACE_FRAMEWORK_IMPORT_EXCEPTIONS = Set.of();

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern FORBIDDEN_FRAMEWORK_IMPORT = Pattern.compile(
            "(?m)^import\\s+(io\\.quarkus|io\\.smallrye|jakarta\\.(annotation\\.security|enterprise|inject|persistence|ws\\.rs)|org\\.eclipse\\.microprofile)\\.");
    private static final Pattern FORBIDDEN_FRAMEWORK_ANNOTATION = Pattern.compile(
            "@(ApplicationScoped|RequestScoped|Inject|Path|GET|POST|PUT|DELETE|PATCH|Produces|Consumes|RolesAllowed|WithSession|WithTransaction|Entity|Table)\\b");
    private static final Pattern FRAMEWORK_IMPORT = Pattern.compile("(?m)^import\\s+br\\.com\\.fiapx\\.[a-z0-9_]+\\.framework\\.");
    private static final Pattern PUBLIC_INSTANCE_METHOD = Pattern.compile(
            "(?m)^\\s+public\\s+(?!static\\b|record\\b|class\\b|interface\\b|enum\\b)([^\\s(]+(?:<[^\\n{;()]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");
    /**
     * O que a borda pode devolver. Uni e a regra; RestMulti e a excecao do download em
     * streaming, e nao um afrouxamento da regra: o handler de streaming do RESTEasy Reactive
     * olha o retorno <b>direto</b> do metodo, entao um Multi embrulhado em Uni ou em Response
     * nao stream-a (medido: o primeiro sai como toString() do objeto, o segundo pendura a
     * conexao). Os dois tipos sao igualmente nao-bloqueantes, que e o que esta regra protege.
     */
    private static final Set<String> RESOURCE_RETURN_TYPES = Set.of("Uni<", "RestMulti<");
    private static final Pattern PUBLIC_RESOURCE_METHOD = Pattern.compile(
            "(?m)^\\s+public\\s+(?!record\\b|class\\b|interface\\b|enum\\b)([^\\s(]+(?:<[^\\n{;()]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");
    /**
     * Mensageria e agendamento sao infraestrutura: o consumidor/publicador/gatilho mora em
     * framework.dispatcher, nunca em core ou interfaces (contrato de mensagens, tickets 014 e 017).
     */
    private static final Pattern MENSAGERIA_OU_AGENDAMENTO_ANOTACAO = Pattern.compile(
            "@(Incoming|Outgoing|Scheduled)\\(");
    /**
     * Executar um processo externo (o ffmpeg do `extracao`, ticket 006 e 015) e infraestrutura
     * igual a mensageria: so framework.service pode instanciar um ProcessBuilder. O core fala
     * em ExtracaoDeFramesGateway, nunca em processo.
     */
    private static final Pattern PROCESSO_EXTERNO = Pattern.compile("\\bnew\\s+ProcessBuilder\\b");
    /**
     * Publicar sem publish-confirms perde mensagem em silencio: o send completa quando o byte
     * sai no socket, nao quando o broker aceita, entao uma recusa do broker vira ack do
     * consumidor e a mensagem some, e a varredura do ADR 0003 nao alcanca o Video perdido
     * (docs/contratos/mensagens.md, ADR 0001, ADR 0003, tickets 027 e 029). O default do
     * conector e false, e o mesmo buraco ja nasceu duas vezes em dois servicos, as duas
     * achado por medicao ou revisao manual. Daqui em diante quem acha e o build (ticket 034).
     * Vale tambem para servico que hoje nao publica: a regra protege o servico, nao o canal
     * que existe.
     *
     * <p>Casa chave, valor e prefixo de perfil de uma linha de canal de saida. O separador
     * aceita "=", ":" e espaco porque .properties aceita os tres, e o nome do canal e ganancioso
     * porque canal com ponto e legal — os dois seriam saidas silenciosas de uma regra cujo
     * ponto inteiro e nao ter saida silenciosa.
     */
    private static final Pattern CHAVE_DE_CANAL_DE_SAIDA = Pattern.compile(
            "^(%[^.=:\\s]+\\.)?(mp\\.messaging\\.outgoing\\.[^=:\\s]+)\\s*[=:\\s]\\s*(.*)$");
    private static final String SUFIXO_CONECTOR = ".connector";
    private static final String SUFIXO_CONFIRMS = ".publish-confirms";
    private static final String CONECTOR_RABBITMQ = "smallrye-rabbitmq";

    @Test
    void deveManterLayoutModularComCoreInterfacesEFramework() {
        var violations = new ArrayList<String>();
        var layersByModule = new TreeMap<String, Set<String>>();

        for (SourceFile source : javaSources()) {
            var packageName = packageName(source);
            if (!packageName.startsWith(BASE_PACKAGE + ".")) {
                violations.add(source.relativePath() + " declara pacote fora de " + BASE_PACKAGE + ": " + packageName);
                continue;
            }

            var packageSuffix = packageName.substring((BASE_PACKAGE + ".").length());
            var packageParts = packageSuffix.split("\\.");
            var moduleName = packageParts[0];

            if (packageParts.length == 1) {
                if (!SHARED_PACKAGES_WITHOUT_LAYER.contains(packageName)) {
                    violations.add(source.relativePath() + " deve estar em <base>.<modulo>.<core|interfaces|framework>: " + packageName);
                }
                continue;
            }

            var layerName = packageParts[1];
            if (REQUIRED_MODULE_LAYERS.contains(layerName)) {
                layersByModule.computeIfAbsent(moduleName, ignored -> new TreeSet<>()).add(layerName);
                continue;
            }

            if (!isSharedPackageWithoutLayer(packageName)) {
                violations.add(source.relativePath() + " usa camada nao prevista para modulo: " + packageName);
            }
        }

        if (!layersByModule.keySet().equals(Set.of(MODULO_DO_SERVICO))) {
            violations.add("Este servico deve conter exatamente o modulo de negocio " + MODULO_DO_SERVICO
                    + ", mas contem " + layersByModule.keySet());
        }

        layersByModule.forEach((moduleName, layers) -> {
            if (!layers.containsAll(REQUIRED_MODULE_LAYERS)) {
                violations.add("Modulo " + moduleName + " deve manter as camadas " + REQUIRED_MODULE_LAYERS
                        + ", mas possui " + layers);
            }
        });

        assertNoViolations(violations);
    }

    @Test
    void coreNaoPodeDependerDeFrameworkHttpPersistenciaOuCdi() {
        var coreSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/core/"))
                .toList();

        assertFalse(coreSources.isEmpty(), "Nenhum fonte em core foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : coreSources) {
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_IMPORT, violations,
                    "core nao deve importar framework HTTP, CDI, persistencia ou MicroProfile");
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_ANNOTATION, violations,
                    "core nao deve declarar anotacoes de framework");
            if (FRAMEWORK_IMPORT.matcher(source.content()).find()
                    && !CORE_FRAMEWORK_IMPORT_EXCEPTIONS.contains(source.relativePath())) {
                violations.add(source.relativePath()
                        + ": core nao deve importar framework; mova o tipo compartilhado para core ou interfaces");
            }
            if (source.content().contains("Uni<") || source.content().contains("subscribeAsCompletionStage()")) {
                violations.add(source.relativePath() + ": core deve expor CompletableFuture, nao Uni/Mutiny");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void interfacesDevemSerClassesPurasSemAnotacoesDeFramework() {
        var interfaceSources = javaSources().stream()
                .filter(source -> isLayer(source, "interfaces"))
                .toList();

        assertFalse(interfaceSources.isEmpty(), "Nenhum fonte em interfaces foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : interfaceSources) {
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_IMPORT, violations,
                    "interfaces nao devem importar framework HTTP, CDI, persistencia ou MicroProfile");
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_ANNOTATION, violations,
                    "interfaces nao devem declarar anotacoes de framework");

            if (FRAMEWORK_IMPORT.matcher(source.content()).find()
                    && !INTERFACE_FRAMEWORK_IMPORT_EXCEPTIONS.contains(source.relativePath())) {
                violations.add(source.relativePath()
                        + ": interfaces nao devem importar framework; mova o tipo compartilhado para interfaces ou core");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void controllersDevemMapearRequestsParaUseCasesSemConhecerHttp() {
        var controllerSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/interfaces/controllers/"))
                .filter(source -> source.relativePath().endsWith("Controller.java"))
                .toList();

        assertFalse(controllerSources.isEmpty(), "Nenhum controller foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : controllerSources) {
            if (!packageName(source).endsWith(".interfaces.controllers")) {
                violations.add(source.relativePath() + " deve estar em pacote .interfaces.controllers");
            }
            if (!source.content().contains("import java.util.concurrent.CompletableFuture;")) {
                violations.add(source.relativePath() + " deve expor operacoes assincronas com CompletableFuture");
            }

            var matcher = PUBLIC_INSTANCE_METHOD.matcher(source.content());
            while (matcher.find()) {
                var returnType = matcher.group(1);
                var methodName = matcher.group(2);
                if (!returnType.startsWith("CompletableFuture<")) {
                    violations.add(source.relativePath() + ": metodo publico de instancia " + methodName
                            + " deve retornar CompletableFuture, mas retorna " + returnType);
                }
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void useCasesDevemUsarMetodoExecutarECompletableFuture() {
        var useCaseSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/core/usecases/"))
                .filter(source -> source.relativePath().endsWith("UseCase.java"))
                .toList();

        assertFalse(useCaseSources.isEmpty(), "Nenhum use case foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : useCaseSources) {
            if (!source.content().contains("CompletableFuture<")) {
                violations.add(source.relativePath() + " deve retornar CompletableFuture");
            }
            if (!source.content().contains(" executar(")) {
                violations.add(source.relativePath() + " deve ter metodo principal executar(...)");
            }
            if (source.content().contains("Command command") && !source.content().contains("record Command")) {
                violations.add(source.relativePath() + " usa Command mas nao declara record Command interno");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void resourcesDevemConterSomenteBordaHttpReativa() {
        var resourceSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/framework/web/"))
                .filter(source -> source.relativePath().endsWith("Resource.java"))
                .toList();

        // Servico sem borda HTTP (worker puro) simplesmente nao tem resource: nada a validar.

        var violations = new ArrayList<String>();
        for (SourceFile source : resourceSources) {
            if (!packageName(source).contains(".framework.web")) {
                violations.add(source.relativePath() + " deve estar abaixo de .framework.web");
            }
            if (!source.content().contains("@Path(")) {
                violations.add(source.relativePath() + " deve declarar @Path na borda HTTP");
            }
            if (!source.content().contains("import io.smallrye.mutiny.Uni;")) {
                violations.add(source.relativePath() + " deve adaptar CompletableFuture para Uni");
            }

            var matcher = PUBLIC_RESOURCE_METHOD.matcher(source.content());
            while (matcher.find()) {
                var returnType = matcher.group(1);
                var methodName = matcher.group(2);
                if (RESOURCE_RETURN_TYPES.stream().noneMatch(returnType::startsWith)) {
                    violations.add(source.relativePath() + ": metodo publico " + methodName
                            + " deve retornar " + RESOURCE_RETURN_TYPES + ", mas retorna " + returnType);
                }
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void bordaNaoPodeBuscarVideoSemDono() {
        var violations = new ArrayList<String>();

        javaSources().stream()
                .filter(source -> source.relativePath().endsWith("Resource.java")
                        || source.relativePath().endsWith("Controller.java"))
                .filter(source -> source.content().contains(".buscarPorId("))
                .forEach(source -> violations.add(source.relativePath()
                        + ": Resource e controller nao podem buscar Video sem Dono; use buscarPorIdEDono"));

        assertNoViolations(violations);
    }

    @Test
    void dataSourceAdaptersDevemImplementarGatewayComApplicationScopedECompletionStage() {
        var adapterSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/framework/"))
                .filter(source -> source.relativePath().endsWith("DataSourceAdapter.java"))
                .toList();

        // So o servico dono de estado tem persistencia; nos demais nao ha adapter a validar.

        var violations = new ArrayList<String>();
        for (SourceFile source : adapterSources) {
            if (!source.content().contains("@ApplicationScoped")) {
                violations.add(source.relativePath() + " deve ser @ApplicationScoped");
            }
            if (!source.content().contains(" implements ") || !source.content().contains("Gateway")) {
                violations.add(source.relativePath() + " deve implementar um Gateway do core");
            }
            if (!source.content().contains("CompletableFuture<")) {
                violations.add(source.relativePath() + " deve expor CompletableFuture");
            }
            if (!source.content().contains("subscribeAsCompletionStage()")) {
                violations.add(source.relativePath() + " deve converter Uni para CompletionStage na saida");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void mensageriaEAgendamentoSoDevemApareceEmFramework() {
        var violations = new ArrayList<String>();

        for (SourceFile source : javaSources()) {
            if (isLayer(source, "framework")) {
                continue;
            }
            if (MENSAGERIA_OU_AGENDAMENTO_ANOTACAO.matcher(source.content()).find()) {
                violations.add(source.relativePath()
                        + ": @Incoming/@Outgoing/@Scheduled so podem aparecer em framework");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void processoExternoSoDeveApareceEmFramework() {
        var violations = new ArrayList<String>();

        for (SourceFile source : javaSources()) {
            if (isLayer(source, "framework")) {
                continue;
            }
            if (PROCESSO_EXTERNO.matcher(source.content()).find()) {
                violations.add(source.relativePath() + ": ProcessBuilder so pode aparecer em framework");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void canalDeSaidaRabbitmqDeveDeclararPublishConfirms() {
        var conector = new TreeMap<String, String>();
        var confirms = new TreeMap<String, String>();
        var canaisDeclarados = new TreeSet<String>();
        var canaisSupostos = new TreeSet<String>();

        for (String linha : configLines()) {
            var matcher = CHAVE_DE_CANAL_DE_SAIDA.matcher(linha);
            if (!matcher.matches()) {
                continue;
            }
            var perfil = matcher.group(1) == null ? "" : matcher.group(1);
            var chave = matcher.group(2);
            var valor = matcher.group(3).strip();

            if (chave.endsWith(SUFIXO_CONECTOR)) {
                var canal = perfil + semSufixo(chave, SUFIXO_CONECTOR);
                canaisDeclarados.add(canal);
                conector.put(canal, valor);
            } else if (chave.endsWith(SUFIXO_CONFIRMS)) {
                var canal = perfil + semSufixo(chave, SUFIXO_CONFIRMS);
                canaisDeclarados.add(canal);
                confirms.put(canal, valor);
            } else {
                // Qualquer outra chave so revela o canal por convencao de nome (o proprio nome
                // pode conter ponto). Vale como pista, e a pista cai fora logo abaixo se algum
                // canal declarado a contiver.
                canaisSupostos.add(perfil + primeiroSegmentoDoCanal(chave));
            }
        }

        canaisSupostos.removeIf(suposto -> canaisDeclarados.stream()
                .anyMatch(declarado -> declarado.startsWith(suposto + ".")));

        var violations = new ArrayList<String>();
        var arquivo = MODULO_DO_SERVICO + "/" + CONFIG_PROPERTIES.toString().replace('\\', '/');
        var canais = new TreeSet<String>(canaisDeclarados);
        canais.addAll(canaisSupostos);

        for (String canal : canais) {
            var conectorDoCanal = valorEfetivo(conector, canal);
            // Sem connector explicito o Quarkus liga o canal ao unico conector do classpath, que
            // nos tres servicos e o RabbitMQ: um canal novo que nasca mudo publica no broker do
            // mesmo jeito, e e exatamente ele que este teste existe para nao deixar passar.
            if (conectorDoCanal != null && !CONECTOR_RABBITMQ.equals(conectorDoCanal)) {
                continue;
            }
            if ("true".equals(valorEfetivo(confirms, canal))) {
                continue;
            }
            // O canal sai com o perfil junto quando tem um: e o prefixo exato da chave que falta.
            violations.add(arquivo + ": canal de saida " + canal + " publica em RabbitMQ"
                    + (conectorDoCanal == null ? " (sem connector explicito, logo pelo unico do classpath)" : "")
                    + " sem " + canal + ".publish-confirms=true; sem confirms a publicacao"
                    + " recusada pelo broker completa como sucesso e a mensagem some em silencio");
        }

        assertNoViolations(violations);
    }

    private static List<SourceFile> javaSources() {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(SourceFile::read)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<String> configLines() {
        assertTrue(Files.exists(CONFIG_PROPERTIES),
                () -> "Nao encontrei " + CONFIG_PROPERTIES + " a partir de " + Path.of("").toAbsolutePath());
        try {
            return Files.readAllLines(CONFIG_PROPERTIES).stream()
                    .map(String::strip)
                    .filter(linha -> !linha.startsWith("#") && !linha.startsWith("!"))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * O valor que o canal enxerga: a chave do proprio perfil vence a chave sem perfil, como no
     * MicroProfile Config. Devolve null quando nenhuma das duas existe.
     */
    private static String valorEfetivo(Map<String, String> valores, String canal) {
        var doPerfil = valores.get(canal);
        return doPerfil != null ? doPerfil : valores.get(semPerfil(canal));
    }

    private static String semPerfil(String canal) {
        return canal.startsWith("%") ? canal.substring(canal.indexOf('.') + 1) : canal;
    }

    private static String semSufixo(String chave, String sufixo) {
        return chave.substring(0, chave.length() - sufixo.length());
    }

    /** "mp.messaging.outgoing.extracao-falhou" a partir de "...extracao-falhou.exchange.name". */
    private static String primeiroSegmentoDoCanal(String chave) {
        var prefixo = "mp.messaging.outgoing.";
        var resto = chave.substring(prefixo.length());
        var ponto = resto.indexOf('.');
        return prefixo + (ponto < 0 ? resto : resto.substring(0, ponto));
    }

    private static String packageName(SourceFile source) {
        var matcher = PACKAGE_DECLARATION.matcher(source.content());
        assertTrue(matcher.find(), () -> source.relativePath() + " nao declara package");
        return matcher.group(1);
    }

    private static boolean isSharedPackageWithoutLayer(String packageName) {
        return SHARED_PACKAGES_WITHOUT_LAYER.stream().anyMatch(sharedPackage ->
                packageName.equals(sharedPackage) || packageName.startsWith(sharedPackage + "."));
    }

    private static boolean isLayer(SourceFile source, String layerName) {
        var packageName = packageName(source);
        if (!packageName.startsWith(BASE_PACKAGE + ".")) {
            return false;
        }

        var packageParts = packageName.substring((BASE_PACKAGE + ".").length()).split("\\.");
        return packageParts.length > 1 && layerName.equals(packageParts[1]);
    }

    private static void assertDoesNotMatch(SourceFile source,
                                           Pattern pattern,
                                           List<String> violations,
                                           String message) {
        if (pattern.matcher(source.content()).find()) {
            violations.add(source.relativePath() + ": " + message);
        }
    }

    private static void assertNoViolations(List<String> violations) {
        assertTrue(violations.isEmpty(), () -> "Violacoes arquiteturais encontradas:\n- "
                + String.join("\n- ", violations));
    }

    private record SourceFile(Path path, String content) {
        static SourceFile read(Path path) {
            try {
                return new SourceFile(path, Files.readString(path));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String relativePath() {
            return path.toString().replace('\\', '/');
        }
    }
}
