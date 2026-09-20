package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import ooo.klae.connex.backend.ai.provider.openai.OpenAiCompatibleAdapter;
import ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiProviderConfiguration;
import ooo.klae.connex.backend.ai.provider.scripted.ScriptedAiProviderProfile;
import ooo.klae.connex.backend.config.DeploymentProfileValidator;

/**
 * Pins every layer that keeps the fixture-driven AI adapter out of a running product.
 *
 * <p>The functional tests prove the scripted provider works; this one proves it cannot be reached.
 * Each rule below corresponds to one deletion a future refactor could plausibly make — a profile
 * annotation, a conditional, a refusal, a forbidden-key entry, an accidentally committed fixture
 * directory, an operator template that activates the seam — and each of those deletions would
 * otherwise leave every other test in the suite green.
 */
class ScriptedAiProviderArchTest {

    private static final String FLAG = ScriptedAiProviderProfile.ENABLED_PROPERTY;
    private static final Path SCRIPTED_PACKAGE = Path.of(
            "backend/src/main/java/ooo/klae/connex/backend/ai/provider/scripted");
    private static final Path VALIDATOR = Path.of(
            "backend/src/main/java/ooo/klae/connex/backend/config/DeploymentProfileValidator.java");
    private static final Path ROUTER = Path.of(
            "backend/src/main/java/ooo/klae/connex/backend/ai/provider/AiProviderRouter.java");

    /**
     * Startup refusals, by the validator field that must carry each one.
     *
     * <p>Refusal one is the suffix appended to the declared deployment profile, so the assertion
     * pins the sentence rather than the whole message.
     */
    private static final Map<String, String> REFUSALS = Map.of(
            "SCRIPTED_PROVIDER_FORBIDS_DEPLOYMENT_PROFILE",
            " forbids the ai-scripted-provider Spring profile",
            "SCRIPTED_PROVIDER_REQUIRES_DEV_OR_TEST",
            "The ai-scripted-provider Spring profile requires the dev or test Spring profile",
            "SCRIPTED_PROVIDER_REQUIRES_FLAG",
            "The ai-scripted-provider Spring profile requires "
                    + "connex.ai.scripted-provider.enabled=true",
            "SCRIPTED_PROVIDER_FLAG_REQUIRES_PROFILE",
            "connex.ai.scripted-provider.enabled requires the ai-scripted-provider Spring profile");

    /**
     * Identifiers no code outside the scripted package may name.
     *
     * <p>The provider is reachable only through the provider router, the journal only through a
     * test that holds the bean, and the interceptor has no implementation at all. A reference from
     * anywhere else is the first step toward a control channel.
     */
    private static final Set<String> SCOPED_TYPES = Set.of(
            "ScriptedAiProvider",
            "ScriptedAiRequestJournal",
            "ScriptedAiStepInterceptor");

    /**
     * Source shapes that would put a step interceptor on the main classpath.
     *
     * <p>A named implementation is only the obvious one. A {@code @Bean} method or a field
     * initialiser returning the functional interface contributes an implementation as a lambda
     * with none of the literals a class declaration would carry, which is precisely how the "zero
     * implementations ship" claim would quietly stop being true.
     */
    private static final List<Pattern> INTERCEPTOR_IMPLEMENTATION_SHAPES = List.of(
            Pattern.compile("implements\\s+ScriptedAiStepInterceptor"),
            Pattern.compile("new\\s+ScriptedAiStepInterceptor"),
            Pattern.compile("\\bScriptedAiStepInterceptor\\s+\\w+\\s*\\("),
            Pattern.compile("\\bScriptedAiStepInterceptor\\s+\\w+\\s*="));

    /** Files permitted to name the scoped types while living outside the scripted package. */
    private static final Set<String> SCOPED_TYPE_ALLOWLIST = Set.of(
            "backend/src/test/java/ooo/klae/connex/backend/architecture/"
                    + "ScriptedAiProviderArchTest.java");

    @Test
    void theRealOpenAiCompatibleAdapterKeepsItsProfileNegation() {
        Profile profile = OpenAiCompatibleAdapter.class.getAnnotation(Profile.class);

        assertNotNull(profile,
                "OpenAiCompatibleAdapter must be excluded when the scripted profile is active, or "
                        + "AiProviderRouter refuses both adapters as a duplicate id");
        assertEquals(List.of("!" + ScriptedAiProviderProfile.NAME), List.of(profile.value()));
    }

    @Test
    void theScriptedConfigurationKeepsBothActivationGates() {
        Profile profile = ScriptedAiProviderConfiguration.class.getAnnotation(Profile.class);
        ConditionalOnProperty conditional = ScriptedAiProviderConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertNotNull(profile, "the scripted configuration must stay behind its Spring profile");
        assertEquals(List.of(ScriptedAiProviderProfile.NAME), List.of(profile.value()));
        assertNotNull(conditional,
                "the scripted configuration must stay behind its explicit enable flag");
        assertEquals("connex.ai.scripted-provider", conditional.prefix());
        assertEquals(List.of("enabled"), List.of(conditional.name()));
        assertEquals("true", conditional.havingValue());
        assertFalse(conditional.havingValue().isBlank(),
                "a blank havingValue reduces the second gate to a presence check, which any "
                        + "value including false would satisfy");
        assertFalse(conditional.matchIfMissing(),
                "matchIfMissing would make the flag optional, leaving the Spring profile as the "
                        + "only gate on the configuration");
    }

    @Test
    void theStartupValidatorKeepsAllFourScriptedRefusals() throws ReflectiveOperationException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> refusal : REFUSALS.entrySet()) {
            String actual = constant(refusal.getKey());
            if (!refusal.getValue().equals(actual)) {
                violations.add(refusal.getKey() + "=" + actual);
            }
        }
        assertTrue(violations.isEmpty(),
                "DeploymentProfileValidator lost or reworded a scripted refusal: " + violations);
    }

    @Test
    void theStartupValidatorStillRunsTheScriptedRefusalsFromItsEvaluation() throws IOException {
        String source = read(VALIDATOR);
        int evaluate = source.indexOf("private static ValidationResult evaluate(");

        assertTrue(evaluate >= 0, "DeploymentProfileValidator.evaluate is missing");
        int firstBranch = source.indexOf("if (profile.isBlank())", evaluate);
        assertTrue(firstBranch > evaluate, "evaluate no longer branches on a blank profile");
        assertTrue(
                source.substring(evaluate, firstBranch).contains("refuseScriptedAiProvider("),
                "the scripted refusals must run before any other deployment-profile branch, so "
                        + "they apply to an edition-less dev stack as well as to a real edition");
    }

    @Test
    void theScriptedFlagStaysOnEveryEditionsForbiddenList() throws ReflectiveOperationException {
        assertTrue(postureKeys().contains(FLAG),
                "the scripted flag must be read as posture or no edition can forbid it");
        List<String> missing = forbiddenKeysByProfile().entrySet().stream()
                .filter(entry -> !entry.getValue().contains(FLAG))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        assertTrue(missing.isEmpty(),
                "deployment profiles that no longer forbid the scripted flag: " + missing);
    }

    @Test
    void theProviderRouterKeepsItsDuplicateIdRefusal() throws IOException {
        assertTrue(read(ROUTER).contains("Duplicate AI provider adapter id"),
                "the scripted adapter answers under openai_compatible, so the router's duplicate "
                        + "refusal is what makes the profile negation load-bearing");
    }

    @Test
    void theScriptedPackageDeclaresNoTransportPersistenceOrPropertiesSurface() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SCRIPTED_PACKAGE)) {
            String source = read(file);
            for (String forbidden : List.of(
                    "@RestController", "@RequestMapping", "@Mapper", "@ConfigurationProperties")) {
                if (source.contains(forbidden)) {
                    violations.add(file.getFileName() + " declares " + forbidden);
                }
            }
        }
        assertTrue(violations.isEmpty(), "scripted package gained a durable surface: " + violations);
    }

    @Test
    void noStepInterceptorImplementationShipsOnTheMainClasspath() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(Path.of("backend/src/main/java"))) {
            String source = read(file);
            for (Pattern shape : INTERCEPTOR_IMPLEMENTATION_SHAPES) {
                if (shape.matcher(source).find()) {
                    violations.add(file.getFileName() + " matches " + shape.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "the running application must contribute no scripted step interceptor: "
                        + violations);
    }

    @Test
    void onlyTheGatedConfigurationDeclaresBeansInTheScriptedPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(SCRIPTED_PACKAGE)) {
            String name = file.getFileName().toString();
            String source = read(file);
            for (String stereotype : List.of(
                    "@Component", "@Service", "@Configuration", "@Bean", "@Repository")) {
                if (source.contains(stereotype)
                        && !"ScriptedAiProviderConfiguration.java".equals(name)) {
                    violations.add(name + " declares " + stereotype);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "every scripted bean must enter the context through the one class that carries "
                        + "both the profile and the flag; a second bean-defining class in this "
                        + "package would be reachable in every edition: " + violations);
    }

    @Test
    void noFixtureShipsInTheArtifact() {
        assertFalse(Files.exists(repoRoot().resolve("backend/src/main/resources/ai/scripted")),
                "no script may ship in the artifact: a shipped build that answers nothing is the "
                        + "layer that survives every other gate being defeated");
    }

    @Test
    void nothingOutsideTheScriptedPackageNamesItsInternalTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(
                Path.of("backend/src/main/java"), Path.of("backend/src/test/java"))) {
            for (Path file : javaFiles(root)) {
                String relative = relative(file);
                if (relative.contains("/ai/provider/scripted/")
                        || SCOPED_TYPE_ALLOWLIST.contains(relative)) {
                    continue;
                }
                String source = read(file);
                for (String type : SCOPED_TYPES) {
                    if (Pattern.compile("\\b" + type + "\\b").matcher(source).find()) {
                        violations.add(relative + " names " + type);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "scripted provider internals escaped their package: " + violations);
    }

    @Test
    void noShippedOperatorTemplateActivatesTheScriptedProvider() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path template : operatorTemplates()) {
            for (String line : read(template).split("\\R")) {
                String setting = line.strip();
                if (setting.startsWith("#")) {
                    continue;
                }
                if (setting.startsWith("SPRING_PROFILES_ACTIVE=")
                        && setting.contains(ScriptedAiProviderProfile.NAME)) {
                    violations.add(
                            template.getFileName() + " activates " + ScriptedAiProviderProfile.NAME);
                }
                if (setting.startsWith("CONNEX_AI_SCRIPTED_PROVIDER_")) {
                    violations.add(template.getFileName() + " sets " + setting);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "shipped operator templates must never carry the scripted seam: " + violations);
    }

    private static List<Path> operatorTemplates() throws IOException {
        try (Stream<Path> templates = Files.list(repoRoot().resolve("deploy"))) {
            return templates
                    .filter(path -> path.getFileName().toString().endsWith(".env.example"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String constant(String name) throws ReflectiveOperationException {
        Field field = DeploymentProfileValidator.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> postureKeys() throws ReflectiveOperationException {
        Field field = DeploymentProfileValidator.class.getDeclaredField("POSTURE_KEYS");
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> forbiddenKeysByProfile()
            throws ReflectiveOperationException {
        Field field = DeploymentProfileValidator.class
                .getDeclaredField("FORBIDDEN_KEYS_BY_PROFILE");
        field.setAccessible(true);
        return (Map<String, List<String>>) field.get(null);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(repoRoot().resolve(path), StandardCharsets.UTF_8);
    }

    private static List<Path> javaFiles(Path relativeRoot) throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot().resolve(relativeRoot))) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String relative(Path path) {
        return repoRoot().relativize(path.toAbsolutePath()).toString();
    }

    private static Path repoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(cwd.resolve("backend"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        return parent == null ? cwd : parent;
    }
}
