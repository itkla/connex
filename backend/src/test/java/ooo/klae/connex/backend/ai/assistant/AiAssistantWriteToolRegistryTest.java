package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.RecordComponent;
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
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.TargetLock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateTask;
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
 * none of the tool's dependencies, and the model's view of an outcome carries no identifier and no
 * undo, approval or verification metadata.
 */
class AiAssistantWriteToolRegistryTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();

    @Test
    void indexesTheDeclaredToolsInCatalogOrderWhateverOrderTheyWereDiscoveredIn() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal")),
                tool("add_tag", ToolTier.AUTO, Set.of("person", "company", "deal")),
                tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                tool("create_activity", ToolTier.AUTO, Set.of("person", "deal"))));

        assertEquals(
                List.of("create_activity", "create_task", "create_note", "add_tag",
                        "change_deal_stage"),
                registry.tools().stream().map(AiAssistantWriteTool::name).toList());
        assertTrue(registry.find("create_task").isPresent());
        assertTrue(registry.find("assign_owner").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void theToolsThisPhaseShipsSatisfyEveryStartupCheck() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                new AiAssistantCreateTaskWriteTool(null, null, null),
                new AiAssistantChangeDealStageWriteTool(null, null),
                new AiAssistantCreateActivityWriteTool(null, null, null),
                new AiAssistantCreateNoteWriteTool(null, null),
                new AiAssistantAddTagWriteTool(null, null, null, null)));

        assertEquals(5, registry.tools().size());
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
    void refusesAcceptedKindsTheExecutorsHandleCheckWouldDisagreeWith() {
        assertRefused("create_task accepts", List.of(
                tool("create_task", ToolTier.AUTO, Set.of("person", "company", "deal"))));
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
    void refusesALegacyLedgerEntryForAToolThatNowHasABean() {
        assertRefused("assign_owner has a write-tool bean and must leave the legacy ledger",
                List.of(
                        tool("create_activity", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("add_tag", ToolTier.AUTO, Set.of("person", "company", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal")),
                        tool("assign_owner", ToolTier.CONFIRM,
                                Set.of("person", "company", "deal"))));
    }

    @Test
    void refusesADeclaredWriteToolWithNeitherABeanNorALedgerEntry() {
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
        expected.removeAll(AiAssistantWriteToolRegistry.LEGACY_TOOLS);
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
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            Class<?> componentType = components[index].getType();
            parameterTypes[index] = componentType;
            if (componentType == String.class) {
                arguments[index] = "Grace Hopper";
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
