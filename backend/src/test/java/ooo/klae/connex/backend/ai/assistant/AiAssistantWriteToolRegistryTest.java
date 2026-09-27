package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Lock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.TargetLock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.CreateTask;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;

/**
 * The context refuses to start on any write-tool declaration that would otherwise fail, or
 * silently misbehave, only when a member reaches it.
 */
class AiAssistantWriteToolRegistryTest {
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();

    @Test
    void indexesTheDeclaredToolsInCatalogOrderWhateverOrderTheyWereDiscoveredIn() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal")),
                tool("create_task", ToolTier.AUTO, Set.of("person", "deal"))));

        assertEquals(
                List.of("create_task", "change_deal_stage"),
                registry.tools().stream().map(AiAssistantWriteTool::name).toList());
        assertTrue(registry.find("create_task").isPresent());
        assertTrue(registry.find("assign_owner").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void theToolsThisPhaseShipsSatisfyEveryStartupCheck() {
        AiAssistantWriteToolRegistry registry = new AiAssistantWriteToolRegistry(catalog, List.of(
                new AiAssistantCreateTaskWriteTool(null, null, null),
                new AiAssistantChangeDealStageWriteTool(null, null)));

        assertEquals(2, registry.tools().size());
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
        assertRefused("create_note has a write-tool bean and must leave the legacy ledger",
                List.of(
                        tool("create_task", ToolTier.AUTO, Set.of("person", "deal")),
                        tool("change_deal_stage", ToolTier.CONFIRM, Set.of("deal")),
                        tool("create_note", ToolTier.AUTO, Set.of("person", "deal"))));
    }

    @Test
    void refusesADeclaredWriteToolWithNeitherABeanNorALedgerEntry() {
        assertRefused(
                "change_deal_stage is declared in the catalog but has no write-tool bean",
                List.of(tool("create_task", ToolTier.AUTO, Set.of("person", "deal"))));
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
        public List<PrincipalRequest> principals(AiAssistantWriteToolRequest request) {
            return List.of();
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
