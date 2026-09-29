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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantAddTagWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantAssignOwnerWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantChangeDealStageWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantCreateActivityWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantCreateNoteWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantCreateTaskWriteTool;
import ooo.klae.connex.backend.ai.assistant.AiAssistantDateResolver;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.PipelineService;
import ooo.klae.connex.backend.services.TagService;
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
 * Holding an allowlisted domain service grants only the methods permitted to that tool, never
 * those granted to another tool holding the same service: a tool may name the service only as the
 * receiver of one of its own, so its unguarded mutators, its unfiltered workspace-wide reads and
 * another tool's writes stay out of reach. The source scan is lexical; the allowlists are what make
 * widening it a reviewed change. Only the framework constructs
 * the unit of work a tool applies, the framework asserts permissions from its locked snapshot and
 * nowhere else on the mutating path, and the lock-order document states the framework's order
 * where the assistant chat section ends. The classes that serve every write tool spell no write
 * tool's key, so a tool's declaration has no second copy for its author to miss.
 */
class AiAssistantWriteToolSpiArchTest {
    private static final Path ASSISTANT_SOURCES =
            Path.of("backend/src/main/java/ooo/klae/connex/backend/ai/assistant");
    private static final Path MAIN_SOURCES = Path.of("backend/src/main/java");
    private static final Path FRAMEWORK =
            ASSISTANT_SOURCES.resolve("AiAssistantWriteToolService.java");
    private static final Path LOCKING = Path.of("docs/backend/LOCKING.md");
    private static final List<Path> TOOL_AGNOSTIC_SOURCES = List.of(
            FRAMEWORK,
            ASSISTANT_SOURCES.resolve("AiAssistantWriteToolRegistry.java"),
            ASSISTANT_SOURCES.resolve("AiAssistantToolCallReadService.java"),
            ASSISTANT_SOURCES.resolve("AiAssistantToolExecutor.java"));

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
     *
     * <p>{@code ActivityService} and {@code NoteService} joined when {@code create_activity} and
     * {@code create_note} moved onto the SPI: each is the domain service those tools already wrote
     * through, and each records its own audit row and asserts its own create and delete
     * permissions. The activity tool's calendar read is not a dependency: the read-tool executor
     * holds a mapper and {@code WorkspaceService}, so the framework performs that read and hands
     * the tool only its answer, through {@code Execution.scheduleConflicts()}.
     *
     * <p>{@code TagService}, {@code PersonService} and {@code CompanyService} joined when
     * {@code add_tag} moved onto the SPI: the tag service holds the workspace's tag vocabulary the
     * tool resolves the requested name against, and the person, company and deal services are the
     * record services {@code add_tag} already associated the tag through, each of which records its
     * own audit row and asserts its own update permission.
     */
    private static final Set<Class<?>> ALLOWED_DEPENDENCIES = Set.of(
            ActivityService.class,
            NoteService.class,
            TaskService.class,
            DealService.class,
            PipelineService.class,
            TagService.class,
            PersonService.class,
            CompanyService.class,
            AiAssistantDateResolver.class,
            ObjectMapper.class);

    /**
     * The only methods each write tool may call on each allowlisted domain service it holds, keyed
     * by tool so a grant to one tool is never a grant to another. Holding a service does not grant
     * all of it: its unguarded {@code update} and {@code delete}, and its unfiltered workspace-wide
     * reads, would bypass the framework's fingerprint-guarded inverse and its restriction-filtered,
     * target-bound schedule read, and another tool's mutator would write a field this tool never
     * declared — a confirm-tier stage change that also tags the deal, or an immediate tag write that
     * also moves a stage. A tool must name the service field only as the receiver of one of its own
     * granted methods, so it cannot hand the service to anything else, and a tool holding a service
     * it has no grant for fails. Widening an entry, or granting a tool a service, is a reviewed
     * decision.
     *
     * <p>{@code add_tag} is granted exactly the calls its legacy arm made: {@code getAllTags}, to
     * resolve the requested name against the workspace's tag vocabulary, which is workspace
     * configuration rather than record data, and each record service's {@code addTag}, which
     * refuses a record the workspace does not hold and a tag it does not hold. Neither
     * {@code removeTag} nor any record update or read is granted: the tool has no inverse, and the
     * framework reads the target through its own scoped gate. {@code change_deal_stage} holds the
     * same {@code DealService} and is granted none of that.
     *
     * <p>{@code assign_owner} is granted exactly the call its legacy arm made: each record service's
     * {@code updateOwner}, which asserts its own update permission, locks the named owner's
     * membership, records its audit row and returns the updated record the tool reads the owner id
     * back from. No read, no member lookup and no other mutator is granted: the framework resolved
     * and locked the owner before the tool runs, and reads the target through its own scoped gate.
     */
    private static final Map<Class<? extends AiAssistantWriteTool>, Map<Class<?>, Set<String>>>
            PERMITTED_SERVICE_METHODS = Map.of(
                    AiAssistantCreateActivityWriteTool.class,
                    Map.of(ActivityService.class, Set.of("create", "deleteIf")),
                    AiAssistantCreateNoteWriteTool.class,
                    Map.of(NoteService.class, Set.of("create", "deleteIf")),
                    AiAssistantCreateTaskWriteTool.class,
                    Map.of(TaskService.class, Set.of("create", "deleteIf")),
                    AiAssistantChangeDealStageWriteTool.class,
                    Map.of(
                            DealService.class, Set.of("changeStage", "getDealById"),
                            PipelineService.class, Set.of("getAllStages")),
                    AiAssistantAddTagWriteTool.class,
                    Map.of(
                            TagService.class, Set.of("getAllTags"),
                            PersonService.class, Set.of("addTag"),
                            CompanyService.class, Set.of("addTag"),
                            DealService.class, Set.of("addTag")),
                    AiAssistantAssignOwnerWriteTool.class,
                    Map.of(
                            PersonService.class, Set.of("updateOwner"),
                            CompanyService.class, Set.of("updateOwner"),
                            DealService.class, Set.of("updateOwner")));

    /**
     * The tools whose read-back is {@code ReadBack.structural}: a comparison of the resolved
     * identifier with itself, which verifies nothing. {@code add_tag} is here because its record
     * services report only whether they created the association, and it is granted no read of the
     * association. Adding a tool is a reviewed decision to ship a write with no verify-after-write.
     */
    private static final Set<String> STRUCTURAL_READ_BACK = Set.of("AiAssistantAddTagWriteTool");

    private static final Pattern SELF_COMPARED_READ_BACK = Pattern.compile(
            "new\\s+ReadBack\\s*\\(\\s*[^,]+,\\s*([^,]+?)\\s*,\\s*\\1\\s*\\)");

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
    void everyDeclaredWriteToolHasExactlyOneBeanAndEveryBeanIsADeclaredWriteTool()
            throws IOException {
        List<String> beanNames = new ArrayList<>();
        for (Path tool : toolImplementations()) {
            Matcher name = DECLARED_NAME.matcher(read(tool));
            assertTrue(name.find(), tool + " must declare its catalog key as NAME");
            beanNames.add(name.group(1));
        }
        Set<String> covered = new TreeSet<>(beanNames);
        assertEquals(beanNames.size(), covered.size(), "a write tool has two beans: " + beanNames);
        assertEquals(
                new TreeSet<>(AiAssistantToolCatalog.writeToolNames()),
                covered,
                "every declared write tool needs exactly one bean, and every bean a declared tool");
    }

    /**
     * The classes that serve every write tool hold no per-tool arm: each reads the tool's own
     * declaration through the registry, which refuses to start without a bean for every catalog
     * write tool. A switch arm, a table entry or a ledger naming one tool would be a second
     * declaration that nothing forces a new tool's author to find, so none of these sources may
     * spell a write tool's key.
     */
    @Test
    void noClassServingEveryWriteToolNamesOne() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : TOOL_AGNOSTIC_SOURCES) {
            String text = read(source);
            for (String name : AiAssistantToolCatalog.writeToolNames()) {
                if (text.contains("\"" + name + "\"")) {
                    violations.add(source.getFileName() + " names " + name);
                }
            }
        }
        assertEquals(List.of(), violations);
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
    void everyDomainServiceAToolHoldsIsCalledOnlyThroughItsPermittedMethods() throws Exception {
        Set<Class<?>> granted = PERMITTED_SERVICE_METHODS.values().stream()
                .flatMap(grants -> grants.keySet().stream())
                .collect(Collectors.toSet());
        assertEquals(
                ALLOWED_DEPENDENCIES.stream()
                        .filter(AiAssistantWriteToolSpiArchTest::isDomainService)
                        .collect(Collectors.toSet()),
                granted,
                "every allowlisted domain service needs a permitted-method list for some tool");
        Set<Class<?>> tools = new HashSet<>();
        List<String> violations = new ArrayList<>();
        for (Path tool : toolImplementations()) {
            Class<?> type = toolClass(tool);
            tools.add(type);
            violations.addAll(unpermittedToolUses(type, read(tool)));
        }
        assertEquals(
                tools,
                PERMITTED_SERVICE_METHODS.keySet(),
                "every write tool, and only a write tool, has its own permitted-method grants");
        assertEquals(List.of(), violations);
    }

    @Test
    void thePermittedMethodScanRefusesABypassOfTheFrameworkRead() {
        Set<String> permitted = PERMITTED_SERVICE_METHODS
                .get(AiAssistantCreateActivityWriteTool.class)
                .get(ActivityService.class);
        String source = """
                private final ActivityService activityService;
                Object a = activityService.create(activity);
                Object b = activityService.getActivitiesByPersonIdInWindow(31, s, e, 101);
                Runnable c = () -> activityService.delete(73);
                Object d = helper(activityService);
                """;

        assertEquals(
                List.of(
                        "calls activityService.getActivitiesByPersonIdInWindow",
                        "calls activityService.delete",
                        "passes activityService on"),
                unpermittedServiceUses(source, "activityService", permitted));
    }

    @Test
    void aGrantToOneToolIsNotAGrantToAnotherHoldingTheSameService() throws Exception {
        String stageTool = read(ASSISTANT_SOURCES.resolve(
                "AiAssistantChangeDealStageWriteTool.java"));
        String tagTool = read(ASSISTANT_SOURCES.resolve("AiAssistantAddTagWriteTool.java"));

        assertEquals(
                List.of("AiAssistantChangeDealStageWriteTool calls dealService.addTag"),
                unpermittedToolUses(
                        AiAssistantChangeDealStageWriteTool.class,
                        stageTool + "\nboolean tagged = dealService.addTag(target.id(), 9);\n"));
        assertEquals(
                List.of(
                        "AiAssistantAddTagWriteTool calls dealService.changeStage",
                        "AiAssistantAddTagWriteTool calls dealService.getDealById"),
                unpermittedToolUses(
                        AiAssistantAddTagWriteTool.class,
                        tagTool + "\nObject moved = dealService.changeStage(change);"
                                + "\nObject deal = dealService.getDealById(target.id());\n"));
        assertEquals(
                List.of("AiAssistantAddTagWriteTool calls personService.updateOwner"),
                unpermittedToolUses(
                        AiAssistantAddTagWriteTool.class,
                        tagTool + "\nObject owned = personService.updateOwner(target.id(), 21);\n"));
        String ownerTool = read(ASSISTANT_SOURCES.resolve("AiAssistantAssignOwnerWriteTool.java"));
        assertEquals(
                List.of("AiAssistantAssignOwnerWriteTool calls companyService.addTag"),
                unpermittedToolUses(
                        AiAssistantAssignOwnerWriteTool.class,
                        ownerTool + "\nboolean tagged = companyService.addTag(target.id(), 9);\n"));
        assertEquals(
                List.of("TaskToolHoldingADealService holds " + DealService.class.getName()
                        + " with no permitted-method grant"),
                unpermittedToolUses(
                        TaskToolHoldingADealService.class,
                        "private final TaskService taskService;\n"
                                + "private final DealService dealService;\n"
                                + "Object created = taskService.create(task);\n",
                        PERMITTED_SERVICE_METHODS.get(AiAssistantCreateTaskWriteTool.class)));
    }

    @Test
    void aReadBackThatCannotDivergeIsDeclaredStructuralOnlyWhereReviewed() throws IOException {
        Set<String> structural = new TreeSet<>();
        List<String> disguised = new ArrayList<>();
        for (Path tool : toolImplementations()) {
            String source = read(tool);
            String name = tool.getFileName().toString().replace(".java", "");
            if (source.contains("ReadBack.structural(")) {
                structural.add(name);
            }
            if (SELF_COMPARED_READ_BACK.matcher(source).find()) {
                disguised.add(name);
            }
        }
        assertEquals(List.of(), disguised, "a read-back compares an identifier with itself");
        assertEquals(new TreeSet<>(STRUCTURAL_READ_BACK), structural);
        assertTrue(SELF_COMPARED_READ_BACK
                .matcher("new ReadBack(\"tagId\", tag.getId(), tag.getId())").find());
        assertFalse(SELF_COMPARED_READ_BACK
                .matcher("new ReadBack(\"stageId\", resolution.id(), changed.getStageId())")
                .find());
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

    /**
     * Every use a tool's source makes of the domain services it holds that its own grants do not
     * permit, including holding a domain service it has no grant for at all.
     */
    private static List<String> unpermittedToolUses(Class<?> type, String source) {
        return unpermittedToolUses(
                type, source, PERMITTED_SERVICE_METHODS.getOrDefault(type, Map.of()));
    }

    private static List<String> unpermittedToolUses(
            Class<?> type, String source, Map<Class<?>, Set<String>> grants) {
        List<String> violations = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !isDomainService(field.getType())) {
                continue;
            }
            Set<String> permitted = grants.get(field.getType());
            if (permitted == null) {
                violations.add(type.getSimpleName() + " holds " + field.getType().getName()
                        + " with no permitted-method grant");
                continue;
            }
            for (String use : unpermittedServiceUses(source, field.getName(), permitted)) {
                violations.add(type.getSimpleName() + " " + use);
            }
        }
        return violations;
    }

    private static boolean isDomainService(Class<?> type) {
        return type.getName().startsWith("ooo.klae.connex.backend.services.");
    }

    /**
     * Every use of a service field in a tool's source that is neither its declaration nor a call of
     * one of its permitted methods.
     */
    private static List<String> unpermittedServiceUses(
            String source, String field, Set<String> permitted) {
        List<String> uses = new ArrayList<>();
        Matcher use = Pattern.compile(
                "\\b" + Pattern.quote(field) + "\\b(\\s*(?:\\.|::)\\s*(\\w+))?")
                .matcher(source);
        while (use.find()) {
            String method = use.group(2);
            if (method == null) {
                if (!source.substring(use.end()).stripLeading().startsWith(";")
                        || !isDeclaration(source, use.start())) {
                    uses.add("passes " + field + " on");
                }
            } else if (!permitted.contains(method)) {
                uses.add("calls " + field + "." + method);
            }
        }
        return uses;
    }

    private static boolean isDeclaration(String source, int fieldStart) {
        int lineStart = source.lastIndexOf('\n', fieldStart) + 1;
        return source.substring(lineStart, fieldStart).trim().startsWith("private final ");
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

    /** A task tool that also holds a deal service it was never granted. */
    private static final class TaskToolHoldingADealService {
        private TaskService taskService;
        private DealService dealService;
    }
}
