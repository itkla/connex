package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
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

import ooo.klae.connex.backend.ai.assistant.AiAssistantDateResolver;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.TaskService;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the assistant write-tool SPI the only way a write tool is added, and keeps each tool
 * unable to bypass what the framework guarantees.
 *
 * <p>The strongest guards are structural. A tool's injected dependencies — constructor parameters
 * and instance fields, found by reflection — must come from an explicit allowlist of domain
 * services and pure helpers, so a tool cannot hold a mapper (and so cannot write without the audit
 * row its domain service records), cannot hold {@code WorkspaceService} or any other member or
 * permission source (and so cannot re-resolve a member or read a permission), and cannot reach
 * either through an assistant helper that does; adding a dependency is a reviewed edit here. The
 * {@code Authority} a tool receives has no field saying why the write was authorized, so a tool
 * cannot branch on it, and the only member lookup a tool is handed is the directory passed to
 * {@code principals}, which {@code Execution} does not carry.
 *
 * <p>Behind those, a source scan refuses a tool that names a locking method, a permission read, or
 * a lifecycle, consent, restriction or authorization mutator — as a call or a method reference.
 * The source scan is lexical and a determined author can evade it through an allowlisted
 * dependency; the allowlist is what makes that a reviewed change. Only the framework constructs
 * the unit of work a tool applies, the framework asserts permissions from its locked snapshot and
 * nowhere else on the mutating path, and the lock-order document states the framework's order
 * where the assistant chat section ends.
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

    private static final List<String> PERMISSION_READS = List.of(
            "permissionsFor",
            "requirePermission",
            "hasPermission",
            "lockedMemberPermissionsFor");

    /**
     * Every type a write tool may be injected with: domain services, whose writes record their own
     * audit row and run their own permission checks, and pure helpers. A new entry is a reviewed
     * decision — never a mapper, {@code WorkspaceService}, a user or member service, or an
     * assistant helper that injects any of them.
     */
    private static final Set<Class<?>> ALLOWED_DEPENDENCIES = Set.of(
            TaskService.class,
            DealService.class,
            PipelineService.class,
            AiAssistantDateResolver.class,
            ObjectMapper.class);

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
    void noWriteToolNamesAMapperALockAPermissionReadOrAForbiddenMutator() throws IOException {
        List<Path> tools = toolImplementations();
        assertTrue(!tools.isEmpty(), "no AiAssistantWriteTool implementation was found");
        List<String> violations = new ArrayList<>();
        for (Path tool : tools) {
            String source = read(tool);
            if (source.contains("ooo.klae.connex.backend.mappers")) {
                violations.add(tool.getFileName() + " reaches a mapper");
            }
            for (String method : LOCKING_METHODS) {
                if (names(source, method)) {
                    violations.add(tool.getFileName() + " takes a lock through " + method);
                }
            }
            for (String method : PERMISSION_READS) {
                if (names(source, method)) {
                    violations.add(tool.getFileName() + " reads a permission through " + method);
                }
            }
            for (String method : FORBIDDEN_MUTATORS) {
                if (names(source, method)) {
                    violations.add(tool.getFileName() + " calls forbidden mutator " + method);
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void everyWriteToolDependencyIsAnAllowlistedDomainServiceOrHelper() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path tool : toolImplementations()) {
            Class<?> type = toolClass(tool);
            List<Class<?>> dependencies = new ArrayList<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                dependencies.addAll(List.of(constructor.getParameterTypes()));
            }
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    dependencies.add(field.getType());
                }
            }
            for (Class<?> dependency : dependencies) {
                if (!ALLOWED_DEPENDENCIES.contains(dependency)) {
                    violations.add(type.getSimpleName() + " depends on " + dependency.getName());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void aToolIsToldWhoActsButNeverWhyAndIsHandedNoMemberLookupToWrite() {
        assertEquals(
                List.of("workspaceId", "userId", "toolCallId", "at"),
                Stream.of(AiAssistantWriteTool.Authority.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
        List<String> nestedTypes = Stream.of(AiAssistantWriteTool.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .toList();
        assertFalse(nestedTypes.contains("Kind"), "the SPI declares an authority kind");
        for (RecordComponent component
                : AiAssistantWriteTool.Execution.class.getRecordComponents()) {
            assertNotEquals(
                    AiAssistantWriteTool.MemberDirectory.class,
                    component.getType(),
                    "apply may not be handed a member lookup");
        }
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
        assertFalse(
                Pattern.compile("\\.requirePermission\\(").matcher(framework).find(),
                "the write framework reads no permission outside its locked snapshot");
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

    private static boolean names(String source, String method) {
        return Pattern.compile("(?:\\." + method + "\\w*\\(|::" + method + "\\w*\\b)")
                .matcher(source)
                .find();
    }

    private static Class<?> toolClass(Path source) throws ClassNotFoundException {
        String simpleName = source.getFileName().toString().replace(".java", "");
        return Class.forName(AiAssistantWriteTool.class.getPackageName() + "." + simpleName);
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
