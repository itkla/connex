package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;

/**
 * Keeps the assistant write-tool SPI the only way a write tool is added, and keeps each tool
 * unable to bypass what the framework guarantees.
 *
 * <p>A write tool may not reach a mapper, so it cannot write without the audit row its domain
 * service records; may not take a lock, so the framework's single order is the only order; may not
 * call a lifecycle, consent, restriction or authorization mutator, which is the partial behavioural
 * guard behind the declarative never-writable field policy; may not branch on why a write was
 * authorized; and may not re-resolve a member. Only the framework constructs the unit of work a
 * tool applies, the framework asserts permissions from its locked snapshot and nowhere else on the
 * mutating path, and the lock-order document states the framework's order where the assistant
 * chat section ends.
 */
class AiAssistantWriteToolSpiArchTest {
    private static final Path ASSISTANT_SOURCES =
            Path.of("backend/src/main/java/ooo/klae/connex/backend/ai/assistant");
    private static final Path MAIN_SOURCES = Path.of("backend/src/main/java");
    private static final Path FRAMEWORK =
            ASSISTANT_SOURCES.resolve("AiAssistantWriteToolService.java");
    private static final Path LOCKING = Path.of("docs/backend/LOCKING.md");

    private static final Set<String> ORIGINAL_LEGACY_TOOLS = Set.of(
            "create_activity", "create_note", "add_tag", "assign_owner");

    private static final List<String> LOCKING_METHODS = List.of(
            "lockBoardForCreation",
            "lockProcessablePersonForUpdate",
            "lockProcessablePersonForShare",
            "lockOwnedCompanyForUpdate",
            "lockDealForUpdate",
            "lockStageChangeRowsForUpdate",
            "lockAndRequireMember",
            "lockAndRequirePermissions",
            "lockedPermissionsFor");

    private static final List<String> FORBIDDEN_MUTATORS = List.of(
            "updateLifecycleStage",
            "disqualify",
            "suspendProcessing",
            "ceaseProvision",
            "archive",
            "updateRole",
            "updateConsent",
            "suppress",
            "setIntroExcluded");

    private static final Pattern IMPLEMENTS_TOOL =
            Pattern.compile("\\bimplements\\s+AiAssistantWriteTool\\b");
    private static final Pattern DECLARED_NAME =
            Pattern.compile("private static final String NAME = \"([a-z_]+)\";");

    @Test
    void everyDeclaredWriteToolHasExactlyOneBeanOrAPlaceOnTheShrinkingLedger() throws IOException {
        List<String> beanNames = new ArrayList<>();
        for (Path tool : toolImplementations()) {
            Matcher name = DECLARED_NAME.matcher(read(tool));
            assertTrue(name.find(), tool + " must declare its catalog key as NAME");
            beanNames.add(name.group(1));
        }
        Set<String> legacy = legacyTools();
        Set<String> covered = new TreeSet<>(beanNames);
        assertEquals(beanNames.size(), covered.size(), "a write tool has two beans: " + beanNames);
        Set<String> overlap = new HashSet<>(covered);
        overlap.retainAll(legacy);
        assertTrue(overlap.isEmpty(), "tools with a bean are still on the ledger: " + overlap);
        covered.addAll(legacy);
        assertEquals(
                new TreeSet<>(AiAssistantToolCatalog.writeToolNames()),
                covered,
                "every declared write tool needs a bean or a legacy-ledger entry, and nothing else");
    }

    @Test
    void theLegacyLedgerOnlyShrinks() {
        Set<String> legacy = legacyTools();
        assertTrue(
                ORIGINAL_LEGACY_TOOLS.containsAll(legacy),
                "a tool may leave AiAssistantWriteToolRegistry.LEGACY_TOOLS but never join it: "
                        + legacy);
    }

    @Test
    void noWriteToolReachesAMapperTakesALockOrCallsAForbiddenMutator() throws IOException {
        List<Path> tools = toolImplementations();
        assertTrue(!tools.isEmpty(), "no AiAssistantWriteTool implementation was found");
        List<String> violations = new ArrayList<>();
        for (Path tool : tools) {
            String source = read(tool);
            if (source.contains("ooo.klae.connex.backend.mappers")) {
                violations.add(tool.getFileName() + " reaches a mapper");
            }
            for (String method : LOCKING_METHODS) {
                if (Pattern.compile("\\." + method + "\\w*\\(").matcher(source).find()) {
                    violations.add(tool.getFileName() + " takes a lock through " + method);
                }
            }
            for (String method : FORBIDDEN_MUTATORS) {
                if (Pattern.compile("\\." + method + "\\w*\\(").matcher(source).find()) {
                    violations.add(tool.getFileName() + " calls forbidden mutator " + method);
                }
            }
            if (source.contains("Authority.Kind") || source.contains("authority().kind()")) {
                violations.add(tool.getFileName() + " branches on why the write was authorized");
            }
            if (source.contains("getMembers(")) {
                violations.add(tool.getFileName() + " can re-resolve a member");
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void onlyTheFrameworkBuildsTheUnitOfWorkAToolApplies() throws IOException {
        List<String> builders = new ArrayList<>();
        for (Path source : javaFiles(MAIN_SOURCES)) {
            String text = read(source);
            boolean qualified = text.contains("new AiAssistantWriteTool.Execution(")
                    || text.contains("new AiAssistantWriteTool.Authority(");
            boolean imported = text.contains("AiAssistantWriteTool")
                    && (text.contains("new Execution(") || text.contains("new Authority("));
            if (qualified || imported) {
                builders.add(source.getFileName().toString());
            }
        }
        assertEquals(List.of("AiAssistantWriteToolService.java"), builders);
    }

    @Test
    void theFrameworkReadsNoPermissionOffTheMutatingPath() throws IOException {
        String framework = read(FRAMEWORK);
        assertTrue(
                !framework.contains("permissionsFor("),
                "the write framework asserts permissions from its locked snapshot only");
        Matcher requirePermission = Pattern.compile("\\.requirePermission\\(").matcher(framework);
        int calls = 0;
        while (requirePermission.find()) {
            calls++;
            String enclosing = framework.substring(0, requirePermission.start());
            int method = enclosing.lastIndexOf("\n    private ");
            int publicMethod = enclosing.lastIndexOf("\n    public ");
            String header = framework.substring(
                    Math.max(method, publicMethod), requirePermission.start());
            assertTrue(
                    header.contains("requireReadableSession("),
                    "requirePermission may only run on the read-only session gate");
        }
        assertEquals(1, calls, "the read-only session gate is the one unlocked permission read");
    }

    @Test
    void theLockOrderDocumentStatesTheFrameworkOrderInsideTheAssistantChatSection()
            throws IOException {
        String locking = read(LOCKING);
        int chat = locking.indexOf("\n## AI assistant chat\n");
        int writeTools = locking.indexOf("\n### Assistant write tools\n");
        int runLeases = locking.indexOf("\n### AI run leases\n");
        assertTrue(chat >= 0 && writeTools > chat && runLeases > writeTools,
                "the assistant write-tool section sits inside AI assistant chat, before run leases");
        assertEquals(writeTools, locking.lastIndexOf("\n### Assistant write tools\n"),
                "the assistant write-tool order is stated once");
        String section = locking.substring(writeTools, runLeases).replaceAll("\\s+", " ");
        for (String statement : List.of(
                "AiAssistantWriteTool",
                "session → tool call → turn",
                "`task_board_lock`",
                "owner-scope",
                "freshness",
                "takes no lock of its own",
                "effectiveFor(actorId)")) {
            assertTrue(section.contains(statement),
                    "LOCKING.md's assistant write-tool section must state: " + statement);
        }
    }

    private static Set<String> legacyTools() {
        try {
            Field ledger = Class.forName(
                    "ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRegistry")
                    .getDeclaredField("LEGACY_TOOLS");
            ledger.setAccessible(true);
            Set<String> names = new TreeSet<>();
            for (Object name : (Set<?>) ledger.get(null)) {
                names.add(String.valueOf(name));
            }
            return names;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("The legacy write-tool ledger is unreadable", exception);
        }
    }

    private static List<Path> toolImplementations() throws IOException {
        List<Path> tools = new ArrayList<>();
        for (Path source : javaFiles(ASSISTANT_SOURCES)) {
            if (IMPLEMENTS_TOOL.matcher(read(source)).find()) {
                tools.add(source);
            }
        }
        return tools;
    }

    private static List<Path> javaFiles(Path relativeRoot) throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot().resolve(relativeRoot))) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(repoRoot().resolve(path), StandardCharsets.UTF_8);
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
