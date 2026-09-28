package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantDeclaredWriteTools.Discovered;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Inverse;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Lock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.MemberDirectory;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.SharedRequestFlag;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.TargetLock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateTask;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The context refuses to start on any write-tool declaration that would otherwise fail, or
 * silently misbehave, only when a member reaches it.
 *
 * <p>The registry-wide checks run over every production write-tool bean found on the classpath,
 * so a tool added later is held to them without editing this class: principal resolution touches
 * none of the tool's dependencies, the model's view of an outcome carries no identifier and no
 * undo, approval or verification metadata, and a tool shares with a viewer who may not read its
 * details only the outcome flags {@link #SHARED_OUTCOME_FLAGS} and the request flags
 * {@link #SHARED_REQUEST_FLAGS} have reviewed, and only the tools on
 * {@link #UNSCREENED_REQUEST_SUMMARIES} decline the special-care screen of their request summary.
 */
class AiAssistantWriteToolRegistryTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * The reviewed outcome flags each tool may share with a viewer who may not read its details.
     *
     * <p>Such a viewer is a shared participant, or a requester who has since lost sight of the
     * target, and {@code detailsReadable} exists to withhold record state from them; a shared flag
     * passes that gate. So a flag may say only how the write went — {@code add_tag}'s
     * {@code changed}, whether this call created the association — and never a property of the
     * record, such as whether it is archived or restricted. Adding a flag, or a tool with one, is a
     * reviewed edit here.
     */
    private static final Map<String, Set<String>> SHARED_OUTCOME_FLAGS =
            Map.of("add_tag", Set.of("changed"));

    /**
     * The reviewed request flags each tool may share with a viewer who may not read its details.
     *
     * <p>A request flag passes the same gate an outcome flag does, so it may say only what kind of
     * write was asked for — {@code assign_owner}'s {@code removesOwner}, which a completed card has
     * always told every participant as "Owner removed" or "Owner assigned" — and never a value the
     * request names. The ledger pins the whole declaration, the field and the keyword as well as
     * the name, so what a flag compares is reviewed too. Adding a flag, or a tool with one, is a
     * reviewed edit here.
     */
    private static final Map<String, Map<String, SharedRequestFlag>> SHARED_REQUEST_FLAGS =
            Map.of("assign_owner",
                    Map.of("removesOwner", new SharedRequestFlag("owner", "unassigned")));

    /**
     * The reviewed tools whose detailed request summary is not screened for special-care text.
     *
     * <p>{@code assign_owner}'s names only the member the owner resolves to against the
     * workspace's own member list, which its pending card already states unscreened as the
     * proposed owner, so screening it would withhold nothing and only rewrite what the card has
     * always said. Adding a tool here is a reviewed edit.
     */
    private static final Set<String> UNSCREENED_REQUEST_SUMMARIES = Set.of("assign_owner");

    /**
     * The reviewed request text each tool's card requires before it projects a stored row: the two
     * confirm-tier tools name the value they propose, and a stored row without it is never shown.
     */
    private static final Map<String, Set<String>> REQUIRED_REQUEST_TEXT = Map.of(
            "assign_owner", Set.of("owner"),
            "change_deal_stage", Set.of("stage"));
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();

    @Test
    void indexesTheDeclaredToolsInCatalogOrderWhateverOrderTheyWereDiscoveredIn() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal")),
                tool("add_tag", ToolTier.AUTO, Set.of("person", "company", "deal")),
                tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                tool("assign_owner", ToolTier.CONFIRM, Set.of("person", "company", "deal")),
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                tool("create_activity", ToolTier.AUTO, Set.of("person", "deal"))));

        assertEquals(
                List.of("create_activity", "create_task", "create_note", "add_tag",
                        "change_deal_stage", "assign_owner"),
                registry.tools().stream().map(AiAssistantWriteTool::name).toList());
        assertTrue(registry.find("create_task").isPresent());
        assertTrue(registry.find("assign_owner").isPresent());
        assertTrue(registry.find("search_records").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void theToolsThisPhaseShipsSatisfyEveryStartupCheck() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                new AiAssistantCreateTaskWriteTool(null, null, null),
                new AiAssistantChangeDealStageWriteTool(null, null),
                new AiAssistantCreateActivityWriteTool(null, null, null),
                new AiAssistantCreateNoteWriteTool(null, null),
                new AiAssistantAddTagWriteTool(null, null, null, null),
                new AiAssistantAssignOwnerWriteTool(null, null, null)));

        assertEquals(6, registry.tools().size());
    }

    @Test
    void refusesADuplicateName() {
        assertRefused("create_task is registered twice", List.of(
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"))));
    }

    @Test
    void refusesANameTheCatalogDoesNotDeclareAsAWrite() {
        assertRefused("search_records is not a declared assistant write tool", List.of(
                tool("search_records", ToolTier.AUTO, Set.of("person"))));
        assertRefused("delete_everything is not a declared assistant write tool", List.of(
                tool("delete_everything", ToolTier.AUTO, Set.of("person"))));
    }

    @Test
    void refusesATierThatDisagreesWithTheCatalog() {
        assertRefused("create_task declares tier CONFIRM but the catalog declares AUTO", List.of(
                tool("create_task", ToolTier.CONFIRM, Set.of("person", "deal"))));
    }

    @Test
    void refusesAnAcceptedKindOutsideTheRecordKinds() {
        assertRefused("create_task must accept a non-empty subset", List.of(
                tool("create_task", ToolTier.AUTO, Set.of("person", "task"))));
        assertRefused("create_task must accept a non-empty subset", List.of(
                tool("create_task", ToolTier.AUTO, Set.of())));
    }

    @Test
    void refusesAKindWithNoRequiredPermission() {
        assertRefused("change_deal_stage requires no permission for deal", List.of(
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                new FakeTool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"),
                        Set.of(), new Lock(false, TargetLock.DEAL_STAGE_CHANGE),
                        Set.of("deal.stageId"))));
    }

    @Test
    void refusesASharedPersonLockOnAnythingButAPerson() {
        assertRefused("create_task declares a shared person lock for deal", List.of(
                new FakeTool("create_task", ToolTier.AUTO, Set.of("person", "deal"),
                        Set.of(Permission.TASK_CREATE),
                        new Lock(true, TargetLock.PERSON_SHARE),
                        Set.of("task.description"))));
    }

    @Test
    void refusesADeclaredFieldNoAssistantToolMayEverWrite() {
        for (String field : AiAssistantWriteFieldPolicy.NEVER_WRITABLE) {
            assertRefused("create_task declares never-writable fields", List.of(
                    new FakeTool("create_task", ToolTier.AUTO, Set.of("person", "deal"),
                            Set.of(Permission.TASK_CREATE),
                            new Lock(true, TargetLock.RECORD_UPDATE),
                            Set.of("task.description", field))));
        }
        assertRefused("create_task declares malformed writable field task", List.of(
                new FakeTool("create_task", ToolTier.AUTO, Set.of("person", "deal"),
                        Set.of(Permission.TASK_CREATE),
                        new Lock(true, TargetLock.RECORD_UPDATE),
                        Set.of("task"))));
    }

    @Test
    void refusesEveryCatalogWriteToolThatHasNoBean() {
        assertRefused(
                "assign_owner is declared in the catalog but has no write-tool bean",
                List.of(
                        tool("create_activity", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("add_tag", ToolTier.AUTO, Set.of("person", "company", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"))));
        assertRefused(
                "change_deal_stage is declared in the catalog but has no write-tool bean",
                List.of(
                        tool("create_activity", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("add_tag", ToolTier.AUTO, Set.of("person", "company", "deal"))));
        assertRefused(
                "add_tag is declared in the catalog but has no write-tool bean",
                List.of(
                        tool("create_activity", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"))));
        assertRefused(
                "create_note is declared in the catalog but has no write-tool bean",
                List.of(
                        tool("create_activity", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"))));
        assertRefused(
                "create_activity is declared in the catalog but has no write-tool bean",
                List.of(
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal"))));
    }

    @Test
    void everyDeclaredToolIsCoveredByTheRegistryWideChecksBelow() {
        Set<String> expected = new TreeSet<>(AiAssistantToolCatalog.writeToolNames());
        Set<String> discovered = new TreeSet<>();
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            discovered.add(tool.name());
        }
        assertEquals(expected, discovered);
    }

    @Test
    void everyDeclaredToolResolvesItsPrincipalsWithoutTouchingAnyDependency() throws Exception {
        for (Discovered discovered : AiAssistantDeclaredWriteTools.discover()) {
            AiAssistantWriteTool tool = discovered.tool();
            MemberDirectory directory = mock(MemberDirectory.class);
            User member = new User();
            member.setId(21);
            member.setDisplayName("Grace Hopper");
            when(directory.members()).thenReturn(List.of(member));

            tool.principals(sampleRequest(tool.requestType()), directory);

            for (Object dependency : discovered.dependencies()) {
                verifyNoInteractions(dependency);
            }
        }
    }

    @Test
    void noDeclaredToolTellsTheModelAnIdentifierOrUndoAndVerificationMetadata() {
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            ObjectNode stored = JSON.createObjectNode();
            stored.put("status", "executed");
            stored.put("recordType", "deal");
            for (String field : tool.memberOutcomeFields()) {
                stored.put(field, "visible " + field);
            }
            for (String identifier : List.of(
                    "id", "taskId", "entityId", "ownerId", "stageId", "personId", "dealId",
                    "companyId", "tagId", "pipelineId")) {
                stored.put(identifier, 74);
            }
            stored.put("fingerprint", "0f1e2d");
            stored.putObject("verification").put("field", "stageId").put("applied", 9);
            stored.putObject("undo").put("status", "available");
            stored.putObject("approval").put("status", "approved");

            Map<String, Object> model = tool.modelOutcome(stored);

            List<String> leaked = model.keySet().stream()
                    .filter(key -> key.equals("id") || key.endsWith("Id")
                            || Set.of("fingerprint", "verification", "undo", "approval")
                                    .contains(key))
                    .toList();
            assertEquals(List.of(), leaked, tool.name() + " tells the model " + leaked);
            assertFalse(model.containsValue(74), tool.name() + " tells the model an identifier");
            assertFalse(
                    model.containsValue("0f1e2d"), tool.name() + " tells the model a fingerprint");
        }
    }

    @Test
    void everyDeclaredToolSharesOnlyItsReviewedOutcomeFlags() {
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            assertEquals(
                    SHARED_OUTCOME_FLAGS.getOrDefault(tool.name(), Set.of()),
                    tool.sharedOutcomeFlags(),
                    tool.name() + " shares outcome flags nobody reviewed with a viewer who may"
                            + " not read its details");
        }
    }

    @Test
    void everyDeclaredToolSharesOnlyItsReviewedRequestFlags() throws Exception {
        for (Discovered discovered : AiAssistantDeclaredWriteTools.discover()) {
            AiAssistantWriteTool tool = discovered.tool();
            Map<String, SharedRequestFlag> declared = tool.sharedRequestFlags();

            assertEquals(
                    SHARED_REQUEST_FLAGS.getOrDefault(tool.name(), Map.of()),
                    declared,
                    tool.name() + " shares request flags nobody reviewed with a viewer who may"
                            + " not read its details");
            for (Map.Entry<String, SharedRequestFlag> flag : declared.entrySet()) {
                String keyword = flag.getValue().literal();
                JsonNode naming = JSON.valueToTree(sampleRequest(
                        tool.requestType(), " " + keyword.toUpperCase() + " "));
                JsonNode assigning = JSON.valueToTree(sampleRequest(tool.requestType()));
                assertTrue(
                        flag.getValue().holds(naming),
                        tool.name() + "'s " + flag.getKey() + " misses its keyword");
                assertFalse(
                        flag.getValue().holds(assigning),
                        tool.name() + "'s " + flag.getKey() + " holds for another value");
            }
            for (Object dependency : discovered.dependencies()) {
                verifyNoInteractions(dependency);
            }
        }
    }

    @Test
    void theOwnerToolsRemovalFlagHoldsOnlyForARemoval() throws Exception {
        SharedRequestFlag removesOwner =
                new AiAssistantAssignOwnerWriteTool(null, null, null)
                        .sharedRequestFlags()
                        .get("removesOwner");

        assertTrue(removesOwner.holds(JSON.readTree("{\"owner\":\" Unassigned \"}")));
        assertFalse(removesOwner.holds(JSON.readTree("{\"owner\":\"Grace Hopper\"}")));
        assertFalse(removesOwner.holds(JSON.readTree("{\"owner\":\"unassigned later\"}")));
        assertFalse(removesOwner.holds(JSON.readTree("{\"owner\":true}")));
        assertFalse(removesOwner.holds(JSON.readTree("{\"handle\":\"unassigned\"}")));
        assertFalse(removesOwner.holds(null));
    }

    @Test
    void onlyTheReviewedToolsDeclineTheRequestSummaryScreen() {
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            assertEquals(
                    !UNSCREENED_REQUEST_SUMMARIES.contains(tool.name()),
                    tool.screensDetailedRequestSummary(),
                    tool.name() + " declines the special-care screen without review");
        }
    }

    @Test
    void refusesAMalformedSharedRequestFlag() {
        Map<String, SharedRequestFlag> unnamed = new HashMap<>();
        unnamed.put(null, new SharedRequestFlag("owner", "unassigned"));
        Map<String, SharedRequestFlag> keywordless = new HashMap<>();
        keywordless.put("removesOwner", null);

        assertRefused(
                "assign_owner declares malformed shared request flag removes owner",
                shippedToolsWithOwnerFlags(
                        Map.of("removes owner", new SharedRequestFlag("owner", "unassigned"))));
        assertRefused(
                "assign_owner declares malformed shared request flag null",
                shippedToolsWithOwnerFlags(unnamed));
        assertRefused(
                "assign_owner declares shared request flag removesOwner without a keyword",
                shippedToolsWithOwnerFlags(keywordless));
        assertRefused(
                "assign_owner declares no shared request flags",
                shippedToolsWithOwnerFlags(null));
        assertThrows(IllegalStateException.class, () -> new SharedRequestFlag("owner", " "));
        assertThrows(IllegalStateException.class, () -> new SharedRequestFlag(null, "unassigned"));
    }

    @Test
    void everyDeclaredToolRequiresOnlyItsReviewedRequestText() {
        for (AiAssistantWriteTool tool : AiAssistantDeclaredWriteTools.tools()) {
            assertEquals(
                    REQUIRED_REQUEST_TEXT.getOrDefault(tool.name(), Set.of()),
                    tool.requiredRequestText(),
                    tool.name() + " requires request text nobody reviewed");
        }
    }

    @Test
    void refusesRequiredRequestTextItsRequestTypeDoesNotDeclareAsAString() {
        assertRefused(
                "assign_owner requires request text ownerId that its request type does not"
                        + " declare",
                shippedToolsWithOwnerRequiredText(Set.of("ownerId")));
        assertRefused(
                "assign_owner declares no required request text",
                shippedToolsWithOwnerRequiredText(null));
    }

    @Test
    void servesTheRequestFlagsItReadAtStartup() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(
                catalog,
                shippedToolsWithOwnerFlags(
                        Map.of("removesOwner", new SharedRequestFlag("owner", "unassigned"))));

        assertEquals(
                Map.of("removesOwner", new SharedRequestFlag("owner", "unassigned")),
                registry.sharedRequestFlags("assign_owner"));
        assertEquals(Map.of(), registry.sharedRequestFlags("add_tag"));
        assertEquals(Map.of(), registry.sharedRequestFlags("search_records"));
        assertEquals(Map.of(), registry.sharedRequestFlags(null));
    }

    @Test
    void anInverseCannotSetAnUndoKeyTheFrameworkOwns() {
        for (String key : Inverse.FRAMEWORK_KEYS) {
            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> new Inverse(
                            "task", 74, "0f1e2d", true, Map.of(key, "2099-01-01T00:00:00Z")));
            assertEquals(
                    "An assistant inverse may not set the framework's undo key " + key,
                    refused.getMessage());
        }
        assertEquals(
                Map.of("tagId", 5),
                new Inverse("tag", 31, "present:5", false, Map.of("tagId", 5)).extra());
    }

    /** A request of the tool's declared record type, every text field filled. */
    private static AiAssistantWriteToolRequest sampleRequest(
            Class<? extends AiAssistantWriteToolRequest> type) throws Exception {
        return sampleRequest(type, "Grace Hopper");
    }

    /** A request of the tool's declared record type, every text field holding {@code text}. */
    private static AiAssistantWriteToolRequest sampleRequest(
            Class<? extends AiAssistantWriteToolRequest> type, String text) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            Class<?> componentType = components[index].getType();
            parameterTypes[index] = componentType;
            if (componentType == String.class) {
                arguments[index] = text;
            } else if (componentType == int.class || componentType == Integer.class) {
                arguments[index] = 1;
            } else if (componentType == boolean.class || componentType == Boolean.class) {
                arguments[index] = Boolean.FALSE;
            }
        }
        return type.getDeclaredConstructor(parameterTypes).newInstance(arguments);
    }

    private void assertRefused(String message, List<AiAssistantWriteTool> tools) {
        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> new AiAssistantWriteToolRegistry(catalog, tools));
        assertTrue(
                refused.getMessage().contains(message),
                () -> "expected \"" + message + "\" in \"" + refused.getMessage() + "\"");
    }

    /** The tools this phase ships, the owner tool declaring {@code flags} as its request flags. */
    private static List<AiAssistantWriteTool> shippedToolsWithOwnerFlags(
            Map<String, SharedRequestFlag> flags) {
        return List.of(
                new AiAssistantCreateTaskWriteTool(null, null, null),
                new AiAssistantChangeDealStageWriteTool(null, null),
                new AiAssistantCreateActivityWriteTool(null, null, null),
                new AiAssistantCreateNoteWriteTool(null, null),
                new AiAssistantAddTagWriteTool(null, null, null, null),
                new AiAssistantAssignOwnerWriteTool(null, null, null) {
                    @Override
                    public Map<String, SharedRequestFlag> sharedRequestFlags() {
                        return flags;
                    }
                });
    }

    /** The tools this phase ships, the owner tool requiring {@code required} as request text. */
    private static List<AiAssistantWriteTool> shippedToolsWithOwnerRequiredText(
            Set<String> required) {
        return List.of(
                new AiAssistantCreateTaskWriteTool(null, null, null),
                new AiAssistantChangeDealStageWriteTool(null, null),
                new AiAssistantCreateActivityWriteTool(null, null, null),
                new AiAssistantCreateNoteWriteTool(null, null),
                new AiAssistantAddTagWriteTool(null, null, null, null),
                new AiAssistantAssignOwnerWriteTool(null, null, null) {
                    @Override
                    public Set<String> requiredRequestText() {
                        return required;
                    }
                });
    }

    private static AiAssistantWriteTool tool(String name, ToolTier tier, Set<String> kinds) {
        return new FakeTool(
                name, tier, kinds, Set.of(Permission.DEAL_UPDATE),
                new Lock(false, TargetLock.RECORD_UPDATE),
                Set.of());
    }

    /** A declaration only; the registry never calls a write-side method. */
    private record FakeTool(
            String name,
            ToolTier tier,
            Set<String> acceptedTargetKinds,
            Set<Permission> permissions,
            Lock lockDeclaration,
            Set<String> declaredWritableFields) implements AiAssistantWriteTool {

        @Override
        public Class<? extends AiAssistantWriteToolRequest> requestType() {
            return CreateTask.class;
        }

        @Override
        public Set<Permission> requiredPermissions(String targetKind) {
            return permissions;
        }

        @Override
        public Lock lock(String targetKind) {
            return lockDeclaration;
        }

        @Override
        public List<PrincipalRequest> principals(
                AiAssistantWriteToolRequest request, MemberDirectory directory) {
            return List.of();
        }

        @Override
        public Set<ReviewInput> reviewInputs() {
            return Set.of();
        }

        @Override
        public Outcome apply(Execution execution) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean inverseAvailable() {
            return false;
        }

        @Override
        public Diff diff(Review review) {
            return null;
        }

        @Override
        public String requestSummary(Review review) {
            return name;
        }

        @Override
        public String outcomeSummary(Review review) {
            return name;
        }

        @Override
        public Map<String, Object> modelOutcome(JsonNode storedOutcome) {
            return Map.of();
        }

        @Override
        public List<String> memberOutcomeFields() {
            return List.of();
        }
    }
}
